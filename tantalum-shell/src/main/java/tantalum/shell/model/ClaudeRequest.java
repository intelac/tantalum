package tantalum.shell.model;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.AssistantMessageParam;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.RedactedThinkingBlockParam;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingBlockParam;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolChoiceNone;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlockParam;
import com.anthropic.models.messages.UserMessageParam;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tantalum.shell.json.JsonText;
import tantalum.value.json.Json;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.Message;
import tantalum.value.model.Prompt;
import tantalum.value.model.ReasoningBlock;
import tantalum.value.model.RequestOptions;
import tantalum.value.model.Role;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolResultBlock;
import tantalum.value.model.ToolSpec;

/**
 * 组 Claude 请求。先算出选项，请求里的模型、输出上限、工具开关、思考、effort、beta 和备用模型都从选项读，
 * 所以 journal 记下的选项就是实际发出的。选项只来自设置和 prompt 的输出约束，不碰客户端，也就拿不到密钥和地址。
 */
final class ClaudeRequest {

    static final String PROVIDER = "claude";
    private static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";
    private static final String BINDING_BETA = "thinking-binding-controls-2026-08-01";
    /** 默认 5 分钟的缓存，每次命中会刷新；一条工具链里相邻两圈相隔几秒到几十秒。 */
    private static final CacheControlEphemeral CACHE = CacheControlEphemeral.builder().build();

    private final String model;
    private final long maxTokens;
    private final String effort;
    private final boolean fallbacks;
    private final boolean strictThinking;

    ClaudeRequest(ClaudeClient.Settings settings) {
        this.model = settings.model();
        this.maxTokens = settings.maxTokens();
        this.effort = settings.effort();
        this.fallbacks = settings.fallbacks();
        this.strictThinking = settings.strictThinking();
    }

    /** {@code extra} 的键名和请求体一致；{@code betas} 是请求头，只在打开时出现，{@code fallbacks} 同理。 */
    RequestOptions options(Prompt prompt) {
        Map<String, Json> thinking = new LinkedHashMap<>();
        thinking.put("type", new Json.Str("adaptive"));
        thinking.put("display", new Json.Str("summarized"));
        if (strictThinking) {
            thinking.put("block_binding", new Json.Obj(Map.of("prefix_mismatch_behavior", new Json.Str("error"))));
        }
        Map<String, Json> extra = new LinkedHashMap<>();
        extra.put("thinking", new Json.Obj(thinking));
        extra.put("output_config", new Json.Obj(Map.of("effort", new Json.Str(effort))));
        List<Json> betas = new ArrayList<>();
        if (fallbacks) {
            betas.add(new Json.Str(FALLBACK_BETA));
        }
        if (strictThinking) {
            betas.add(new Json.Str(BINDING_BETA));
        }
        if (!betas.isEmpty()) {
            extra.put("betas", new Json.Arr(betas));
        }
        if (fallbacks) {
            extra.put("fallbacks", new Json.Str("default"));
        }
        Prompt.Output output = prompt.output();
        return new RequestOptions(PROVIDER, model, output.maxTokens() > 0 ? output.maxTokens() : maxTokens, output.toolsAllowed(),
                new Json.Obj(extra));
    }

    /**
     * 按选项组装请求并放 prompt caching 断点。缓存层级是固定的“工具 → system → 消息”，前缀逐字节相同才命中，所以断点放在
     * 最后一个工具定义、system 块和最后一条消息的最后一个内容块上：工具和 system 在整个运行里不变，历史只追加，
     * 上一圈的最后一条消息正好是这一圈前缀的末尾。钩子每圈追加不同的 system 文字会让 system 之后的缓存失效，工具段仍命中。
     */
    static MessageCreateParams params(Prompt prompt, RequestOptions options) {
        Map<String, Json> extra = ((Json.Obj) options.extra()).fields();
        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(options.model())
                .maxTokens(options.maxTokens())
                .thinking(thinking((Json.Obj) extra.get("thinking")))
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(text(extra.get("output_config"), "effort"))).build());
        if (!options.toolsAllowed()) {
            params.toolChoice(ToolChoiceNone.builder().build());
        }
        if (!prompt.system().isBlank()) {
            params.systemOfTextBlockParams(List.of(
                    TextBlockParam.builder().text(prompt.system()).cacheControl(CACHE).build()));
        }
        List<ToolSpec> tools = prompt.tools();
        for (int i = 0; i < tools.size(); i++) {
            params.addTool(tool(tools.get(i), i == tools.size() - 1));
        }
        List<Message> messages = prompt.messages();
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            List<ContentBlockParam> blocks = new ArrayList<>(message.content().stream()
                    .map(ClaudeRequest::param).filter(Objects::nonNull).toList());
            if (blocks.isEmpty()) {
                continue;
            }
            if (i == messages.size() - 1) {
                blocks.set(blocks.size() - 1, cached(blocks.getLast()));
            }
            if (message.role() == Role.USER) {
                params.addMessage(UserMessageParam.builder().contentOfContentBlockParams(blocks).build());
            } else {
                params.addMessage(AssistantMessageParam.builder().contentOfContentBlockParams(blocks).build());
            }
        }
        if (extra.get("betas") instanceof Json.Arr betas) {
            params.putAdditionalHeader("anthropic-beta", String.join(",", betas.items().stream().map(beta -> ((Json.Str) beta).value()).toList()));
        }
        if (extra.get("fallbacks") instanceof Json.Str fallback) {
            params.putAdditionalBodyProperty("fallbacks", JsonValue.from(fallback.value()));
        }
        return params.build();
    }

    private static String text(Json object, String field) {
        return ((Json.Str) ((Json.Obj) object).fields().get(field)).value();
    }

    private static ThinkingConfigAdaptive thinking(Json.Obj config) {
        ThinkingConfigAdaptive.Builder thinking = ThinkingConfigAdaptive.builder()
                .display(ThinkingConfigAdaptive.Display.of(text(config, "display")));
        Json binding = config.fields().get("block_binding");
        if (binding != null) {
            thinking.putAdditionalProperty("block_binding", JsonValue.from(JsonText.toPlain(binding)));
        }
        return thinking.build();
    }

    private static Tool tool(ToolSpec spec, boolean last) {
        Tool.InputSchema.Builder schema = Tool.InputSchema.builder();
        if (spec.schema() instanceof Json.Obj object) {
            Map<String, JsonValue> fields = new HashMap<>();
            object.fields().forEach((key, value) -> {
                if (!key.equals("type")) {
                    fields.put(key, JsonValue.from(JsonText.toPlain(value)));
                }
            });
            schema.putAllAdditionalProperties(fields);
        }
        Tool.Builder tool = Tool.builder().name(spec.name()).description(spec.description())
                .inputSchema(schema.build()).eagerInputStreaming(true);
        if (last) {
            tool.cacheControl(CACHE);
        }
        return tool.build();
    }

    /** 只有文本和工具结果块能带缓存标记；最后一条消息总是用户消息，所以这两种就够了。 */
    private static ContentBlockParam cached(ContentBlockParam block) {
        if (block.isText()) {
            return ContentBlockParam.ofText(block.asText().toBuilder().cacheControl(CACHE).build());
        }
        if (block.isToolResult()) {
            return ContentBlockParam.ofToolResult(block.asToolResult().toBuilder().cacheControl(CACHE).build());
        }
        return block;
    }

    /** 其他供应商的推理内容和空文本不发送。 */
    private static ContentBlockParam param(ContentBlock block) {
        return switch (block) {
            case TextBlock text -> text.text().isEmpty() ? null : ContentBlockParam.ofText(
                    TextBlockParam.builder().text(text.text()).build());
            case ReasoningBlock reasoning -> reasoningParam(reasoning);
            case ToolCallBlock call -> {
                ToolUseBlockParam.Input.Builder input = ToolUseBlockParam.Input.builder();
                if (call.arguments() instanceof Json.Obj object) {
                    object.fields().forEach((key, value) -> input.putAdditionalProperty(key, JsonValue.from(JsonText.toPlain(value))));
                }
                yield ContentBlockParam.ofToolUse(ToolUseBlockParam.builder()
                        .id(call.id()).name(call.name()).input(input.build()).build());
            }
            case ToolResultBlock result -> ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                    .toolUseId(result.toolCallId()).content(result.text()).isError(result.error()).build());
        };
    }

    private static ContentBlockParam reasoningParam(ReasoningBlock reasoning) {
        if (!reasoning.provider().equals(PROVIDER) || !(reasoning.raw() instanceof Json.Obj raw)) {
            return null;
        }
        if (raw.fields().get("type") instanceof Json.Str type && type.value().equals("redacted_thinking")) {
            return ContentBlockParam.ofRedactedThinking(RedactedThinkingBlockParam.builder()
                    .data(((Json.Str) raw.fields().get("data")).value()).build());
        }
        return ContentBlockParam.ofThinking(ThinkingBlockParam.builder()
                .thinking(((Json.Str) raw.fields().get("thinking")).value())
                .signature(((Json.Str) raw.fields().get("signature")).value()).build());
    }
}
