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
 *   <li>其它：一句思考，然后复述用户的话。</li>
 * </ul>
 */
public final class E2eModel implements ModelClient {

    static final String MODEL = "scripted-e2e";
    static final Duration SLOW_STEP = Duration.ofMillis(250);
    static final int SLOW_STEPS = 40;

    private final AtomicInteger callIds = new AtomicInteger();

    @Override
    public ModelOutcome call(Prompt prompt, ChunkListener chunks) {
        int lastUser = lastUserText(prompt.messages());
        String text = lastUser < 0 ? "" : userText(prompt.messages().get(lastUser));
        int results = toolResultsAfter(prompt.messages(), lastUser);
        try {
            if (text.contains("tools")) {
                return toolChain(results, chunks);
            }
            if (text.contains("slow")) {
                return slow(chunks);
            }
            if (text.contains("fail")) {
                return new ModelOutcome.Failed(FailureReason.SERVER_ERROR, false, null, "", "scripted failure");
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
        return new ModelOutcome.Replied(content, StopReason.END_TURN, MODEL, usage());
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
        return new ModelOutcome.Replied(content, tools ? StopReason.TOOL_USE : StopReason.END_TURN, MODEL, usage());
    }

    private ToolCallBlock call(String tool, Map<String, String> arguments) {
        Map<String, Json> fields = new java.util.LinkedHashMap<>();
        StringBuilder raw = new StringBuilder("{");
        arguments.forEach((name, value) -> {
            if (raw.length() > 1) {
                raw.append(',');
            }
            raw.append('"').append(name).append("\":\"").append(value).append('"');
            fields.put(name, new Json.Str(value));
        });
        raw.append('}');
        return new ToolCallBlock("c" + callIds.incrementAndGet(), tool, raw.toString(), new Json.Obj(fields));
    }

    private static TokenUsage usage() {
        return new TokenUsage(1200, 80, 300, 0);
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
