package tantalum.shell.model;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.RawContentBlockDelta;
import com.anthropic.models.messages.RawContentBlockStartEvent;
import com.anthropic.models.messages.RawMessageDeltaEvent;
import com.anthropic.models.messages.RawMessageStartEvent;
import com.anthropic.models.messages.RawMessageStreamEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tantalum.value.json.Json;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.FailureReason;
import tantalum.value.model.Prompt;
import tantalum.value.model.ReasoningBlock;
import tantalum.value.model.RequestOptions;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tantalum.value.model.TokenUsage;

/**
 * 用官方 SDK 调 Claude。SDK 必须关闭自动重试，由内核负责重试。
 * {@code thinking} 块连同签名原样保存和回传；历史只追加，否则签名失效。组请求在 {@link ClaudeRequest}。
 */
public final class ClaudeClient implements ModelClient {

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
    private final ClaudeRequest request;
    private final Duration firstChunk;
    private final Duration betweenChunks;
    private final OverflowRules overflow;

    /**
     * 模型参数。{@code strictThinking} 让 thinking 签名不匹配时直接 400，未强制校验的账号也能暴露改动历史的问题，测试用。
     */
    public record Settings(String model, long maxTokens, String effort, boolean fallbacks, Duration firstChunk,
                           Duration betweenChunks, OverflowRules overflow, boolean strictThinking) {}

    public ClaudeClient(AnthropicClient client, Settings settings) {
        this.client = client;
        this.request = new ClaudeRequest(settings);
        this.firstChunk = settings.firstChunk();
        this.betweenChunks = settings.betweenChunks();
        this.overflow = settings.overflow();
    }

    @Override
    public RequestOptions options(Prompt prompt) {
        return request.options(prompt);
    }

    @Override
    public ModelOutcome call(Prompt prompt, ChunkListener chunks) {
        return call(prompt, AttachmentMedia.NONE, chunks);
    }

    @Override
    public ModelOutcome call(Prompt prompt, AttachmentMedia media, ChunkListener chunks) {
        StringBuilder visible = new StringBuilder();
        Map<Long, Pending> blocks = new TreeMap<>();
        Reply reply = new Reply();
        StreamResponse<?>[] open = new StreamResponse<?>[1];
        try (StreamWatchdog watchdog = new StreamWatchdog(firstChunk, betweenChunks, () -> closeQuietly(open))) {
            try {
                StreamResponse<RawMessageStreamEvent> stream = client.messages().createStreaming(
                        ClaudeRequest.params(prompt, options(prompt), media));
                open[0] = stream;
                try (stream) {
                    stream.stream().forEach(event -> {
                        watchdog.activity();
                        apply(event, blocks, reply, visible, chunks);
                    });
                }
                return finish(blocks, reply, visible, watchdog);
            } catch (AnthropicServiceException failure) {
                return ModelFailures.http(failure.statusCode(), failure.getMessage(),
                        failure.headers().values("retry-after").stream().findFirst().orElse(null), visible.toString(), overflow);
            } catch (RuntimeException failure) {
                closeQuietly(open);
                if (ModelFailures.isInterrupt(failure)) {
                    return new ModelOutcome.Interrupted(visible.toString());
                }
                if (watchdog.expired()) {
                    return ModelFailures.timeout(visible.toString());
                }
                if (failure instanceof AnthropicIoException) {
                    return ModelFailures.connection(visible.toString(), failure.toString());
                }
                throw failure;
            }
        }
    }

    private static void closeQuietly(StreamResponse<?>[] open) {
        if (open[0] != null) {
            open[0].close();
        }
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
        event.contentBlockStart().ifPresent(start -> {
            Pending block = pending(start);
            blocks.put(start.index(), block);
            if (block.kind.equals("tool_use")) {
                chunks.onToolInput("");
            }
        });
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
        delta.inputJson().ifPresent(piece -> {
            block.text.append(piece.partialJson());
            chunks.onToolInput(piece.partialJson());
        });
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
        boolean refused = com.anthropic.models.messages.StopReason.REFUSAL.equals(reply.stop);
        ModelOutcome cut = ModelFailures.afterStream(visible.toString(), watchdog, reply.stopped || refused);
        if (cut != null) {
            return cut;
        }
        if (refused) {
            return new ModelOutcome.Failed(FailureReason.REFUSAL, false, null, visible.toString(),
                    "refused: " + reply.refusal);
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
                yield new ReasoningBlock(ClaudeRequest.PROVIDER, new Json.Obj(raw));
            }
            case "redacted_thinking" -> {
                Map<String, Json> raw = new LinkedHashMap<>();
                raw.put("type", new Json.Str("redacted_thinking"));
                raw.put("data", new Json.Str(block.data));
                yield new ReasoningBlock(ClaudeRequest.PROVIDER, new Json.Obj(raw));
            }
            case "tool_use" -> {
                String raw = block.text.toString();
                yield ModelFailures.toolCall(block.id, block.name, raw);
            }
            default -> new TextBlock(block.text.toString());
        };
    }
}
