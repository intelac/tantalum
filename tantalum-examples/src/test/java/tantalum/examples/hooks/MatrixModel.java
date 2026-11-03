package tantalum.examples.hooks;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tantalum.shell.model.ChunkListener;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.ModelOutcome;
import tantalum.value.json.Json;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.FailureReason;
import tantalum.value.model.Message;
import tantalum.value.model.Prompt;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolCallBlock;

/**
 * 按会话的脚本回复，记下每个会话收到的 prompt。所有格子共用一个服务，所以从 prompt 文字里的 {@code cell=<会话>} 认出会话。
 * 脚本用完还被调用时抛 {@link AssertionError}，运行会以失败结束，格子的断言随之失败。
 */
final class MatrixModel implements ModelClient {

    private static final Pattern CELL = Pattern.compile("cell=([A-Za-z0-9_-]+)");

    private final Map<String, Deque<ModelOutcome>> scripts = new ConcurrentHashMap<>();
    private final Map<String, List<Prompt>> prompts = new ConcurrentHashMap<>();

    void script(String session, ModelOutcome... outcomes) {
        scripts.put(session, new ArrayDeque<>(List.of(outcomes)));
    }

    List<Prompt> prompts(String session) {
        List<Prompt> seen = prompts.get(session);
        if (seen == null) {
            return List.of();
        }
        synchronized (seen) {
            return List.copyOf(seen);
        }
    }

    int total() {
        return prompts.values().stream().mapToInt(List::size).sum();
    }

    @Override
    public ModelOutcome call(Prompt prompt, ChunkListener chunks) {
        String session = cellOf(prompt);
        prompts.computeIfAbsent(session, key -> Collections.synchronizedList(new ArrayList<>())).add(prompt);
        Deque<ModelOutcome> script = scripts.get(session);
        ModelOutcome next;
        synchronized (this) {
            next = script == null ? null : script.poll();
        }
        if (next == null) {
            throw new AssertionError("script of " + session + " ended, but the model was called again");
        }
        if (next instanceof ModelOutcome.Replied reply) {
            reply.content().stream().filter(TextBlock.class::isInstance)
                    .forEach(block -> chunks.onText(((TextBlock) block).text()));
        }
        return next;
    }

    static String cellOf(Prompt prompt) {
        for (Message message : prompt.messages()) {
            for (ContentBlock block : message.content()) {
                if (block instanceof TextBlock text) {
                    Matcher matcher = CELL.matcher(text.text());
                    if (matcher.find()) {
                        return matcher.group(1);
                    }
                }
            }
        }
        throw new AssertionError("prompt without cell=: " + prompt.messages());
    }

    static ModelOutcome answer(String text) {
        return new ModelOutcome.Replied(List.of(new TextBlock(text)), StopReason.END_TURN, "matrix", null);
    }

    /** 调一次 {@code tool}，参数带上会话，工具据此计数。 */
    static ModelOutcome tool(String session, String id, String tool) {
        return tools(call(session, id, tool));
    }

    static ModelOutcome tools(ToolCallBlock... calls) {
        return new ModelOutcome.Replied(List.of(calls), StopReason.TOOL_USE, "matrix", null);
    }

    static ToolCallBlock call(String session, String id, String tool) {
        return new ToolCallBlock(id, tool, "{\"cell\":\"" + session + "\"}", new Json.Obj(Map.of("cell", new Json.Str(session))));
    }

    static ModelOutcome failed(FailureReason reason, boolean retryable, Duration retryAfter, String detail) {
        return new ModelOutcome.Failed(reason, retryable, retryAfter, "", detail);
    }

    /** 摘要调用的回复：带上 cell=，压缩后的 prompt 仍认得出会话。 */
    static ModelOutcome summary(String session) {
        return answer("## Goal\nSummary of cell=" + session);
    }
}
