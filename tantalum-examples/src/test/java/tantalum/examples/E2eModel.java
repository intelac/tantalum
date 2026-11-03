package tantalum.examples;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import tantalum.shell.model.ChunkListener;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.ModelOutcome;
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

/**
 * 端到端测试用的脚本化模型，不访问任何网络。按最后一条用户消息里的关键词决定剧本：
 * <ul>
 *   <li>{@code tools}：思考 → grep → 思考 → 两个并行 read_file → 回答；</li>
 *   <li>{@code slow}：一段一段慢慢回答，取消时返回已发出的部分；</li>
 *   <li>{@code fail}：供应商出错；</li>
 *   <li>{@code external}：调用 MCP 工具 lookup，然后回答；{@code forbidden}：调用未放行的 MCP 工具 delete_all；</li>
 *   <li>{@code remember}：调用 memory_write 写一条记忆，然后回答“已记住。”；</li>
 *   <li>{@code trace:}（轨迹页）：第一次调用 429，重试后一批并行工具（read_file、被钩子拦下的 read_file secret、MCP lookup），
 *       然后带引用回答；配合 {@link E2eTraceHook}；</li>
 *   <li>其它：一句思考，然后复述用户的话。</li>
 * </ul>
 */
public final class E2eModel implements ModelClient {

    static final String MODEL = "scripted-e2e";
    static final Duration SLOW_STEP = Duration.ofMillis(250);
    static final int SLOW_STEPS = 40;

    private final AtomicInteger callIds = new AtomicInteger();
    /** 已经故意失败过一次的 {@code trace:} 提问，重试时成功。 */
    private final java.util.Set<String> rateLimited = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** 当前这次调用的 prompt 估算；openai 供应商并发为 1，调用是串行的。 */
    private static volatile long promptSize;

    @Override
    public ModelOutcome call(Prompt prompt, ChunkListener chunks) {
        promptSize = promptSize(prompt);
        int lastUser = lastUserText(prompt.messages());
        String text = lastUser < 0 ? "" : userText(prompt.messages().get(lastUser));
        int results = toolResultsAfter(prompt.messages(), lastUser);
        try {
            if (!prompt.output().toolsAllowed()) {
                return summary(prompt);
            }
            if (text.contains("long")) {
                return longAnswer(text, prompt, chunks);
            }
            if (text.contains("truncate")) {
                stream(chunks, "这段回复会被截");
                return new ModelOutcome.Replied(List.of(new TextBlock("这段回复会被截")), StopReason.MAX_TOKENS, MODEL, usage(prompt));
            }
            if (text.contains(E2eTraceHook.KEYWORD)) {
                return traceScenario(text, results, chunks);
            }
            if (text.contains("tools")) {
                return toolChain(results, chunks);
            }
            if (text.contains("slow")) {
                return slow(chunks);
            }
            if (text.contains("fail")) {
                return new ModelOutcome.Failed(FailureReason.SERVER_ERROR, false, null, "", "scripted failure");
            }
            if (text.contains("external")) {
                return results == 0 ? reply(think(chunks, "这要查外部系统。"), call("mcp__demo__lookup", Map.of()))
                        : answer(chunks, "外部系统查到了负责人。");
            }
            if (text.contains("forbidden")) {
                return results == 0 ? reply(List.of(), call("mcp__demo__delete_all", Map.of()))
                        : answer(chunks, "这个工具不可用。");
            }
            if (text.contains("remember")) {
                return remember(results, chunks);
            }
            return echo(text, chunks);
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            return new ModelOutcome.Interrupted(cancelled.getMessage());
        }
    }

    private ModelOutcome toolChain(int results, ChunkListener chunks) throws InterruptedException {
        return switch (results) {
            case 0 -> reply(think(chunks, "先用 grep 找一下 hello 出现在哪些文件里。"),
                    call("grep", Map.of("pattern", "hello")));
            case 1 -> reply(think(chunks, "两个文件都提到了，分别读出来。"),
                    call("read_file", Map.of("path", "notes/hello.md")),
                    call("read_file", Map.of("path", "notes/world.md")));
            default -> answer(chunks, "两个文件都读到了：\n\n- `notes/hello.md` 里是问候\n- `notes/world.md` 里是回应\n\n```text\nhello e2e\n```");
        };
    }

    private ModelOutcome traceScenario(String text, int results, ChunkListener chunks) throws InterruptedException {
        if (results == 0 && rateLimited.add(text)) {
            return new ModelOutcome.Failed(FailureReason.RATE_LIMITED, true, Duration.ofMillis(300), "", "HTTP 429: slow down");
        }
        if (results == 0) {
            return reply(think(chunks, "并行读两个文件，再查一下外部系统。"),
                    call("read_file", Map.of("path", "notes/hello.md")),
                    call("read_file", Map.of("path", "notes/secret.md")),
                    call("mcp__demo__lookup", Map.of()));
        }
        return answer(chunks, "问候在 notes/hello.md:3，工单 42 归 e2e-owner。");
    }

    private ModelOutcome remember(int results, ChunkListener chunks) throws InterruptedException {
        if (results == 0) {
            Map<String, String> arguments = new java.util.LinkedHashMap<>();
            arguments.put("name", "e2e-fact");
            arguments.put("type", "user");
            arguments.put("description", "e2e 测试写入的记忆");
            arguments.put("content", "## e2e\n\n这是脚本化模型写入的记忆正文。");
            return reply(think(chunks, "用户让我记住，写进记忆。"), call("memory_write", arguments));
        }
        return answer(chunks, "已记住。");
    }

    /** 摘要调用：工具被禁用。摘要很短，所以总比它替代的头部小。 */
    private static ModelOutcome summary(Prompt prompt) {
        int lastUser = lastUserText(prompt.messages().subList(0, prompt.messages().size() - 1));
        String last = lastUser < 0 ? "" : userText(prompt.messages().get(lastUser)).split("\n", 2)[0];
        String text = "## Goal\n\n用户在和脚本化模型聊天，最近说的是“" + last + "”。\n\n## Key context\n\n之前的回答都是长篇填充文字。";
        return new ModelOutcome.Replied(List.of(new TextBlock(text)), StopReason.END_TURN, MODEL, usage(prompt));
    }

    /** 约 12 KB 的长回答（估算 4000 token），几轮就越过 e2e 服务器的压缩阈值。 */
    private static ModelOutcome longAnswer(String text, Prompt prompt, ChunkListener chunks) throws InterruptedException {
        String head = "长回答开始：" + text + "\n\n";
        String tail = "\n\n长回答结束：" + text;
        String filler = ("这是用来撑大上下文的填充文字，没有别的意思。").repeat(190);
        stream(chunks, head + tail);
        String answer = head + filler + tail;
        return new ModelOutcome.Replied(List.of(new TextBlock(answer)), StopReason.END_TURN, MODEL,
                usage(promptSize(prompt), tantalum.kernel.compaction.TokenEstimate.ofText(answer)));
    }

    private ModelOutcome slow(ChunkListener chunks) throws InterruptedException {
        StringBuilder sent = new StringBuilder();
        for (int i = 1; i <= SLOW_STEPS; i++) {
            String piece = "慢慢回答第 " + i + " 段。";
            try {
                Thread.sleep(SLOW_STEP);
            } catch (InterruptedException cancelled) {
                throw new InterruptedException(sent.toString());
            }
            chunks.onText(piece);
            sent.append(piece);
        }
        return reply(List.of(), new TextBlock(sent.toString()));
    }

    private ModelOutcome echo(String text, ChunkListener chunks) throws InterruptedException {
        List<ContentBlock> thinking = think(chunks, "用户说了一句话，直接复述。");
        String answer = "你说：" + text;
        stream(chunks, answer);
        List<ContentBlock> content = new ArrayList<>(thinking);
        content.add(new TextBlock(answer));
        return new ModelOutcome.Replied(content, StopReason.END_TURN, MODEL,
                usage(promptSize, tantalum.kernel.compaction.TokenEstimate.ofText(answer)));
    }

    private ModelOutcome answer(ChunkListener chunks, String text) throws InterruptedException {
        stream(chunks, text);
        return reply(List.of(), new TextBlock(text));
    }

    private static void stream(ChunkListener chunks, String text) throws InterruptedException {
        int step = Math.max(1, text.length() / 4);
        for (int at = 0; at < text.length(); at += step) {
            chunks.onText(text.substring(at, Math.min(text.length(), at + step)));
            Thread.sleep(30);
        }
    }

    private static List<ContentBlock> think(ChunkListener chunks, String text) {
        chunks.onReasoning(text);
        return List.of(new ReasoningBlock("scripted", new Json.Str(text)));
    }

    private static ModelOutcome reply(List<ContentBlock> prefix, ContentBlock... blocks) {
        List<ContentBlock> content = new ArrayList<>(prefix);
        content.addAll(List.of(blocks));
        boolean tools = content.stream().anyMatch(ToolCallBlock.class::isInstance);
        long output = 0;
        for (ContentBlock block : content) {
            if (block instanceof TextBlock text) {
                output += tantalum.kernel.compaction.TokenEstimate.ofText(text.text());
            }
        }
        return new ModelOutcome.Replied(content, tools ? StopReason.TOOL_USE : StopReason.END_TURN, MODEL, usage(promptSize, Math.max(output, 20)));
    }

    private ToolCallBlock call(String tool, Map<String, String> arguments) {
        Map<String, Json> fields = new java.util.LinkedHashMap<>();
        StringBuilder raw = new StringBuilder("{");
        arguments.forEach((name, value) -> {
            if (raw.length() > 1) {
                raw.append(',');
            }
            raw.append('"').append(name).append("\":\"").append(value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")).append('"');
            fields.put(name, new Json.Str(value));
        });
        raw.append('}');
        return new ToolCallBlock("c" + callIds.incrementAndGet(), tool, raw.toString(), new Json.Obj(fields));
    }

    /** 用量按估算报：输入是 prompt 的估算，输出是回复的估算，这样状态栏的占比和压缩的锚点都像真的。 */
    private static TokenUsage usage(Prompt prompt) {
        return usage(promptSize(prompt), 80);
    }

    private static long promptSize(Prompt prompt) {
        return tantalum.kernel.compaction.TokenEstimate.ofText(prompt.system())
                + tantalum.kernel.compaction.TokenEstimate.ofTools(prompt.tools())
                + tantalum.kernel.compaction.TokenEstimate.ofMessages(prompt.messages());
    }

    private static TokenUsage usage(long promptSize, long output) {
        return new TokenUsage(Math.max(0, promptSize - 300), output, Math.min(300, promptSize), 0);
    }

    private static int lastUserText(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (!userText(messages.get(i)).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    private static String userText(Message message) {
        if (message.role() != Role.USER) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : message.content()) {
            if (block instanceof TextBlock t) {
                text.append(t.text());
            }
        }
        return text.toString();
    }

    private static int toolResultsAfter(List<Message> messages, int from) {
        int count = 0;
        for (int i = from + 1; i < messages.size(); i++) {
            for (ContentBlock block : messages.get(i).content()) {
                if (block instanceof ToolResultBlock) {
                    count++;
                }
            }
        }
        return count;
    }
}
