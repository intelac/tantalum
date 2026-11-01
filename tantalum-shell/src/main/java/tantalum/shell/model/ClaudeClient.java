package tantalum.shell.model;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.AssistantMessageParam;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.RawContentBlockDelta;
import com.anthropic.models.messages.RawContentBlockStartEvent;
import com.anthropic.models.messages.RawMessageDeltaEvent;
import com.anthropic.models.messages.RawMessageStartEvent;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.RedactedThinkingBlockParam;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingBlockParam;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlockParam;
import com.anthropic.models.messages.UserMessageParam;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tantalum.shell.journal.JsonText;
import tantalum.value.json.Json;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.FailureReason;
import tantalum.value.model.Message;
import tantalum.value.model.Prompt;
import tantalum.value.model.ReasoningBlock;
import tantalum.value.model.Role;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tantalum.value.model.TokenUsage;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolResultBlock;
import tantalum.value.model.ToolSpec;

/**
 * 用官方 SDK 调 Claude。SDK 必须关闭自动重试，由内核负责重试。
 * {@code thinking} 块连同签名原样保存和回传；历史只追加，否则签名失效。
 */
public final class ClaudeClient implements ModelClient {

    private static final String PROVIDER = "claude";
    private static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";
    /** 默认 5 分钟的缓存，每次命中会刷新；一条工具链里相邻两圈相隔几秒到几十秒。 */
    private static final CacheControlEphemeral CACHE = CacheControlEphemeral.builder().build();

    /** 正在累积的一个内容块。 */
    private static final class Pending {
        final String kind;
        final StringBuilder text = new StringBuilder();
        final StringBuilder signature = new StringBuilder();
        String id;
        String name;
        String data;

        Pending(String kind) {
            this.kind = kind;
        }
    }

    private final AnthropicClient client;
    private final String model;
    private final long maxTokens;
    private final String effort;
    private final boolean fallbacks;
    private final Duration firstChunk;
    private final Duration betweenChunks;
    private final OverflowRules overflow;

    public ClaudeClient(AnthropicClient client, String model, long maxTokens, String effort, boolean fallbacks,
                        Duration firstChunk, Duration betweenChunks, OverflowRules overflow) {
        this.client = client;
        this.model = model;
        this.maxTokens = maxTokens;
        this.effort = effort;
        this.fallbacks = fallbacks;
        this.firstChunk = firstChunk;
        this.betweenChunks = betweenChunks;
        this.overflow = overflow;
    }

    @Override
    public ModelOutcome call(Prompt prompt, ChunkListener chunks) {
        StringBuilder visible = new StringBuilder();
        Map<Long, Pending> blocks = new TreeMap<>();
        Reply reply = new Reply();
        StreamResponse<?>[] open = new StreamResponse<?>[1];
        try (StreamWatchdog watchdog = new StreamWatchdog(firstChunk, betweenChunks, () -> closeQuietly(open))) {
            try {
                StreamResponse<RawMessageStreamEvent> stream = client.messages().createStreaming(params(prompt));
                open[0] = stream;
                try (stream) {
                    stream.stream().forEach(event -> {
                        watchdog.activity();
                        apply(event, blocks, reply, visible, chunks);
                    });
                }
                return finish(blocks, reply, visible, watchdog);
            } catch (AnthropicServiceException failure) {
                return serviceFailure(failure, visible);
            } catch (RuntimeException failure) {
                closeQuietly(open);
                if (isInterrupt(failure)) {
                    return new ModelOutcome.Interrupted(visible.toString());
                }
                if (watchdog.expired()) {
                    return timeout(visible);
                }
                if (failure instanceof AnthropicIoException) {
                    return new ModelOutcome.Failed(FailureReason.CONNECTION_ERROR, true, null, visible.toString(), failure.toString());
                }
                throw failure;
            }
        }
    }

    private static boolean isInterrupt(Throwable failure) {
        if (Thread.currentThread().isInterrupted()) {
            return true;
        }
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof InterruptedIOException && !(cause instanceof java.net.SocketTimeoutException)) {
                return true;
            }
        }
        return false;
    }

    private static void closeQuietly(StreamResponse<?>[] open) {
        if (open[0] != null) {
            open[0].close();
        }
    }

    private static ModelOutcome timeout(StringBuilder visible) {
        return new ModelOutcome.Failed(FailureReason.TIMEOUT, true, null, visible.toString(), "model stream timed out");
    }

    private ModelOutcome serviceFailure(AnthropicServiceException failure, StringBuilder visible) {
        int status = failure.statusCode();
        String detail = "HTTP " + status + ": " + failure.getMessage();
        Duration retryAfter = failure.headers().values("retry-after").stream()
                .findFirst().map(OpenAiClient::seconds).orElse(null);
        if (overflow.matches(status, failure.getMessage())) {
            return new ModelOutcome.Failed(FailureReason.CONTEXT_OVERFLOW, false, null, visible.toString(), detail);
        }
        if (status == 429) {
            return new ModelOutcome.Failed(FailureReason.RATE_LIMITED, true, retryAfter, visible.toString(), detail);
        }
        if (status >= 500) {
            return new ModelOutcome.Failed(FailureReason.SERVER_ERROR, true, retryAfter, visible.toString(), detail);
        }
        return new ModelOutcome.Failed(FailureReason.INVALID_REQUEST, false, null, visible.toString(), detail);
    }

    /** 从事件累积出的回复头信息。 */
    private static final class Reply {
        String actualModel;
        long input;
        long output;
        long cacheRead;
        long cacheWrite;
        boolean hasUsage;
        boolean stopped;
        com.anthropic.models.messages.StopReason stop;
        String refusal;
    }

    private void apply(RawMessageStreamEvent event, Map<Long, Pending> blocks, Reply reply, StringBuilder visible,
                       ChunkListener chunks) {
        event.messageStart().ifPresent(start -> onStart(start, reply));
        event.contentBlockStart().ifPresent(start -> blocks.put(start.index(), pending(start)));
        // 没有 start 就来 delta 的块按文本块处理，不让一个异常分片把整次回复变成失败。
        event.contentBlockDelta().ifPresent(delta ->
                onDelta(delta.delta(), blocks.computeIfAbsent(delta.index(), index -> new Pending("text")), visible, chunks));
        event.messageDelta().ifPresent(delta -> onMessageDelta(delta, reply));
        if (event.isMessageStop()) {
            reply.stopped = true;
        }
    }

    private void onStart(RawMessageStartEvent start, Reply reply) {
        reply.actualModel = start.message().model().asString();
        reply.input = start.message().usage().inputTokens();
        reply.output = start.message().usage().outputTokens();
        reply.cacheRead = start.message().usage().cacheReadInputTokens().orElse(0L);
        reply.cacheWrite = start.message().usage().cacheCreationInputTokens().orElse(0L);
        reply.hasUsage = true;
    }

    private static Pending pending(RawContentBlockStartEvent start) {
        RawContentBlockStartEvent.ContentBlock block = start.contentBlock();
        if (block.isThinking()) {
            return new Pending("thinking");
        }
        if (block.isRedactedThinking()) {
            Pending redacted = new Pending("redacted_thinking");
            redacted.data = block.asRedactedThinking().data();
            return redacted;
        }
        if (block.isToolUse()) {
            Pending tool = new Pending("tool_use");
            tool.id = block.asToolUse().id();
            tool.name = block.asToolUse().name();
            return tool;
        }
        return new Pending("text");
    }

    private static void onDelta(RawContentBlockDelta delta, Pending block, StringBuilder visible, ChunkListener chunks) {
        delta.text().ifPresent(piece -> {
            block.text.append(piece.text());
            visible.append(piece.text());
            chunks.onText(piece.text());
        });
        delta.thinking().ifPresent(piece -> {
            block.text.append(piece.thinking());
            chunks.onReasoning(piece.thinking());
        });
        delta.signature().ifPresent(piece -> block.signature.append(piece.signature()));
        delta.inputJson().ifPresent(piece -> block.text.append(piece.partialJson()));
    }

    private static void onMessageDelta(RawMessageDeltaEvent event, Reply reply) {
        RawMessageDeltaEvent.Delta delta = event.delta();
        delta.stopReason().ifPresent(stop -> reply.stop = stop);
        delta.stopDetails().ifPresent(details -> reply.refusal = details.category().map(Object::toString).orElse("unknown")
                + details.explanation().map(text -> ": " + text).orElse(""));
        reply.output = event.usage().outputTokens();
        event.usage().inputTokens().ifPresent(value -> reply.input = value);
        event.usage().cacheReadInputTokens().ifPresent(value -> reply.cacheRead = value);
        event.usage().cacheCreationInputTokens().ifPresent(value -> reply.cacheWrite = value);
    }

    private ModelOutcome finish(Map<Long, Pending> blocks, Reply reply, StringBuilder visible, StreamWatchdog watchdog) {
        if (Thread.currentThread().isInterrupted()) {
            return new ModelOutcome.Interrupted(visible.toString());
        }
        if (watchdog.expired()) {
            return timeout(visible);
        }
        if (com.anthropic.models.messages.StopReason.REFUSAL.equals(reply.stop)) {
            return new ModelOutcome.Failed(FailureReason.REFUSAL, false, null, visible.toString(),
                    "refused: " + reply.refusal);
        }
        if (!reply.stopped) {
            return new ModelOutcome.Failed(FailureReason.CONNECTION_ERROR, true, null, visible.toString(), "stream ended early");
        }
        List<ContentBlock> content = new ArrayList<>();
        blocks.values().forEach(block -> content.add(toBlock(block)));
        TokenUsage usage = reply.hasUsage ? new TokenUsage(reply.input, reply.output, reply.cacheRead, reply.cacheWrite) : null;
        return new ModelOutcome.Replied(content, stopReason(reply.stop), reply.actualModel, usage);
    }

    /** SDK 的停止原因是值对象不是枚举，用 {@code equals} 比较。 */
    private static StopReason stopReason(com.anthropic.models.messages.StopReason stop) {
        if (com.anthropic.models.messages.StopReason.END_TURN.equals(stop)) {
            return StopReason.END_TURN;
        }
        if (com.anthropic.models.messages.StopReason.TOOL_USE.equals(stop)) {
            return StopReason.TOOL_USE;
        }
        if (com.anthropic.models.messages.StopReason.MAX_TOKENS.equals(stop)) {
            return StopReason.MAX_TOKENS;
        }
        return StopReason.OTHER;
    }

    private static ContentBlock toBlock(Pending block) {
        return switch (block.kind) {
            case "thinking" -> {
                Map<String, Json> raw = new LinkedHashMap<>();
                raw.put("type", new Json.Str("thinking"));
                raw.put("thinking", new Json.Str(block.text.toString()));
                raw.put("signature", new Json.Str(block.signature.toString()));
                yield new ReasoningBlock(PROVIDER, new Json.Obj(raw));
            }
            case "redacted_thinking" -> {
                Map<String, Json> raw = new LinkedHashMap<>();
                raw.put("type", new Json.Str("redacted_thinking"));
                raw.put("data", new Json.Str(block.data));
                yield new ReasoningBlock(PROVIDER, new Json.Obj(raw));
            }
            case "tool_use" -> {
                String raw = block.text.toString();
                yield new ToolCallBlock(block.id, block.name, raw, JsonText.parseOrNull(raw.isBlank() ? "{}" : raw));
            }
            default -> new TextBlock(block.text.toString());
        };
    }

    /**
     * 组装请求并放 prompt caching 断点。缓存层级是固定的“工具 → system → 消息”，前缀逐字节相同才命中，所以断点放在
     * 最后一个工具定义、system 块和最后一条消息的最后一个内容块上：工具和 system 在整个运行里不变，历史只追加，
     * 上一圈的最后一条消息正好是这一圈前缀的末尾。钩子每圈追加不同的 system 文字会让 system 之后的缓存失效，工具段仍命中。
     */
    private MessageCreateParams params(Prompt prompt) {
        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxTokens)
                .thinking(ThinkingConfigAdaptive.builder().display(ThinkingConfigAdaptive.Display.SUMMARIZED).build())
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(effort)).build());
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
                    .map(ClaudeClient::param).filter(java.util.Objects::nonNull).toList());
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
        if (fallbacks) {
            params.putAdditionalHeader("anthropic-beta", FALLBACK_BETA);
            params.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }
        return params.build();
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
