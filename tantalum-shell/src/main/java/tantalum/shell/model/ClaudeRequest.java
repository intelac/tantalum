package tantalum.shell.model;

import com.anthropic.core.JsonField;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.AssistantMessageParam;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.Base64PdfSource;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.DocumentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.RedactedThinkingBlockParam;
import com.anthropic.models.messages.SystemMessageParam;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingBlockParam;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolChoiceNone;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlockParam;
import com.anthropic.models.messages.UserMessageParam;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tantalum.shell.json.JsonText;
import tantalum.value.attachment.AttachmentRef;
import tantalum.value.attachment.AttachmentText;
import tantalum.value.attachment.FileRef;
import tantalum.value.attachment.ImageRef;
import tantalum.value.json.Json;
import tantalum.value.model.AttachmentBlock;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.Message;
import tantalum.value.model.ModelChangeBlock;
import tantalum.value.model.Prompt;
import tantalum.value.model.ReasoningBlock;
import tantalum.value.model.RequestOptions;
import tantalum.value.model.Role;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolChangeBlock;
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
    /** 会话中途增减工具（core-tools C14）：工具列表不改，用追加的系统消息 {@code tool_addition}、{@code tool_removal}。 */
    static final String TOOL_CHANGE_BETA = "mid-conversation-tool-changes-2026-07-01";
    /**
     * 支持会话中途工具变化的模型（官方文档 platform-availability，2026-11 核对；Claude Sonnet 5 不支持）。不在名单里的模型不发
     * 工具变化：会话开始时没开的工具不发，中途打开的要等新会话；中途关掉的照样在执行前拒绝。
     */
    static final Set<String> TOOL_CHANGE_MODELS = Set.of("claude-opus-5", "claude-opus-5-5", "claude-opus-4-8", "claude-fable-5",
            "claude-fable-5-1", "claude-mythos-5", "claude-mythos-5-1", "claude-sonnet-5-5");
    /** 默认 5 分钟的缓存，每次命中会刷新；一条工具链里相邻两圈相隔几秒到几十秒。 */
    private static final CacheControlEphemeral CACHE = CacheControlEphemeral.builder().build();
    /** 只有附件、没有文字的用户消息补的文字，Claude 不收空文字块。 */
    static final String SEE_ATTACHED = "(see attached)";
    /** Claude 的 PDF 上限：超过时不发文档块，只给只读路径。 */
    static final long MAX_PDF_BYTES = 32L * 1024 * 1024;
    static final int MAX_PDF_PAGES = 100;
    private static final Base64.Encoder BASE64 = Base64.getEncoder();

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
        // 备用模型不一定认工具变化：出现过工具变化的会话不带备用模型（设计“风险”）
        boolean toolChanges = TOOL_CHANGE_MODELS.contains(model) && ToolChanges.present(prompt);
        boolean withFallbacks = fallbacks && !toolChanges;
        List<Json> betas = new ArrayList<>();
        if (withFallbacks) {
            betas.add(new Json.Str(FALLBACK_BETA));
        }
        if (toolChanges) {
            betas.add(new Json.Str(TOOL_CHANGE_BETA));
        }
        if (strictThinking) {
            betas.add(new Json.Str(BINDING_BETA));
        }
        if (!betas.isEmpty()) {
            extra.put("betas", new Json.Arr(betas));
        }
        if (withFallbacks) {
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
        return params(prompt, options, AttachmentMedia.NONE);
    }

    /** 同上，附件内容从 {@code media} 取。 */
    static MessageCreateParams params(Prompt prompt, RequestOptions options, AttachmentMedia media) {
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
        boolean toolChanges = extra.get("betas") instanceof Json.Arr list && list.items().contains(new Json.Str(TOOL_CHANGE_BETA));
        // 不发工具变化时：会话开始时没开的工具不发，历史里的变化也不发
        List<ToolSpec> tools = toolChanges ? prompt.tools() : prompt.tools().stream().filter(tool -> !tool.deferred()).toList();
        int cachedTool = lastIndexOf(tools, tool -> !tool.deferred());
        for (int i = 0; i < tools.size(); i++) {
            params.addTool(tool(tools.get(i), i == cachedTool));
        }
        List<Message> messages = prompt.messages().stream().filter(message -> toolChanges || message.role() != Role.SYSTEM).toList();
        int cachedMessage = lastIndexOf(messages, message -> message.role() != Role.SYSTEM);
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            if (message.role() == Role.SYSTEM) {
                params.addMessage(systemMessage(message));
                continue;
            }
            List<ContentBlockParam> blocks = blocks(message, media);
            if (blocks.isEmpty()) {
                continue;
            }
            if (i == cachedMessage) {
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

    /**
     * 一条工具变化的系统消息：每个新增、移除的工具一个块，按名字引用（工具定义已在列表里，标了 defer_loading）。SDK 的系统消息
     * 只有文字内容，块用它留给任意 JSON 的口子写。
     */
    @SuppressWarnings("unchecked")
    private static SystemMessageParam systemMessage(Message message) {
        List<Object> content = new ArrayList<>();
        for (ContentBlock block : message.content()) {
            ToolChangeBlock change = (ToolChangeBlock) block;
            change.added().forEach(name -> content.add(toolReference("tool_addition", name)));
            change.removed().forEach(name -> content.add(toolReference("tool_removal", name)));
        }
        JsonField<?> raw = JsonValue.from(content);
        return SystemMessageParam.builder().content((JsonField<SystemMessageParam.Content>) raw).build();
    }

    private static Map<String, Object> toolReference(String type, String name) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", type);
        // 键的顺序要固定：请求逐字节相同，缓存才命中（Map.of 的顺序每次启动都可能不同）
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "tool_reference");
        tool.put("name", name);
        block.put("tool", tool);
        return block;
    }

    private static <T> int lastIndexOf(List<T> items, java.util.function.Predicate<T> test) {
        for (int i = items.size() - 1; i >= 0; i--) {
            if (test.test(items.get(i))) {
                return i;
            }
        }
        return -1;
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
        if (spec.deferred()) {
            tool.putAdditionalProperty("defer_loading", JsonValue.from(true));
        }
        if (last) {
            tool.cacheControl(CACHE);
        }
        return tool.build();
    }

    /** 最后一条消息总是用户消息：文本、工具结果、图片和文档块都可能在最后，都能带缓存标记。 */
    private static ContentBlockParam cached(ContentBlockParam block) {
        if (block.isText()) {
            return ContentBlockParam.ofText(block.asText().toBuilder().cacheControl(CACHE).build());
        }
        if (block.isToolResult()) {
            return ContentBlockParam.ofToolResult(block.asToolResult().toBuilder().cacheControl(CACHE).build());
        }
        if (block.isImage()) {
            return ContentBlockParam.ofImage(block.asImage().toBuilder().cacheControl(CACHE).build());
        }
        if (block.isDocument()) {
            return ContentBlockParam.ofDocument(block.asDocument().toBuilder().cacheControl(CACHE).build());
        }
        return block;
    }

    /**
     * 一条消息的块。附件展开成说明文字加图片或文档块；只有附件没有文字的用户消息在最前面补 {@code (see attached)}
     * （pi {@code anthropic-messages.ts:184-191}）；出错的工具结果里的图片放在结果之后（pi {@code :1334-1341}）。
     */
    private static List<ContentBlockParam> blocks(Message message, AttachmentMedia media) {
        List<ContentBlockParam> blocks = new ArrayList<>();
        boolean attachments = message.content().stream().anyMatch(AttachmentBlock.class::isInstance);
        boolean text = message.content().stream().anyMatch(block -> block instanceof TextBlock plain && !plain.text().isBlank());
        if (message.role() == Role.USER && attachments && !text) {
            blocks.add(ContentBlockParam.ofText(TextBlockParam.builder().text(SEE_ATTACHED).build()));
        }
        List<ContentBlockParam> afterResults = new ArrayList<>();
        for (ContentBlock block : message.content()) {
            switch (block) {
                case AttachmentBlock attachment -> blocks.addAll(attachment(attachment.ref(), media));
                case ToolResultBlock result when !result.images().isEmpty() && result.error() -> {
                    blocks.add(param(result));
                    result.images().forEach(image -> afterResults.add(ContentBlockParam.ofImage(imageParam(image, media))));
                }
                case ToolResultBlock result when !result.images().isEmpty() -> blocks.add(toolResultWithImages(result, media));
                default -> {
                    ContentBlockParam param = param(block);
                    if (param != null) {
                        blocks.add(param);
                    }
                }
            }
        }
        blocks.addAll(afterResults);
        return blocks;
    }

    private static List<ContentBlockParam> attachment(AttachmentRef ref, AttachmentMedia media) {
        return switch (ref) {
            case ImageRef image -> image(image, media);
            case FileRef file -> {
                List<ContentBlockParam> blocks = new ArrayList<>();
                blocks.add(ContentBlockParam.ofText(TextBlockParam.builder().text(AttachmentText.file(file)).build()));
                if (file.pdf() && file.bytes() <= MAX_PDF_BYTES && file.pages() <= MAX_PDF_PAGES) {
                    blocks.add(ContentBlockParam.ofDocument(DocumentBlockParam.builder()
                            .source(Base64PdfSource.builder().data(BASE64.encodeToString(media.file(file))).build()).build()));
                }
                yield blocks;
            }
        };
    }

    /** 图片前面一行说明，再是图片本身（需求 3.3）。 */
    private static List<ContentBlockParam> image(ImageRef ref, AttachmentMedia media) {
        return List.of(ContentBlockParam.ofText(TextBlockParam.builder().text(AttachmentText.image(ref)).build()),
                ContentBlockParam.ofImage(imageParam(ref, media)));
    }

    private static ImageBlockParam imageParam(ImageRef ref, AttachmentMedia media) {
        AttachmentMedia.Image image = media.image(ref);
        return ImageBlockParam.builder().source(Base64ImageSource.builder()
                .mediaType(Base64ImageSource.MediaType.of(image.mediaType()))
                .data(BASE64.encodeToString(image.bytes())).build()).build();
    }

    /** 没出错的工具结果：文字和图片都在结果里（需求 3.7）。图片的说明行已经在工具输出的文字里，不再重复。 */
    private static ContentBlockParam toolResultWithImages(ToolResultBlock result, AttachmentMedia media) {
        List<ToolResultBlockParam.Content.Block> content = new ArrayList<>();
        content.add(ToolResultBlockParam.Content.Block.ofText(TextBlockParam.builder().text(result.text()).build()));
        for (ImageRef image : result.images()) {
            content.add(ToolResultBlockParam.Content.Block.ofImage(imageParam(image, media)));
        }
        return ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                .toolUseId(result.toolCallId()).contentOfBlocks(content).isError(false).build());
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
            case AttachmentBlock attachment -> throw new IllegalStateException("attachments are expanded in blocks()");
            case ToolChangeBlock change -> throw new IllegalStateException("tool changes are system messages, see systemMessage()");
            case ModelChangeBlock change -> ContentBlockParam.ofText(TextBlockParam.builder().text(change.text()).build());
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
