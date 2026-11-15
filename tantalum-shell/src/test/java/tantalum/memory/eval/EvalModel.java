package tantalum.memory.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import tantalum.memory.capture.NotePrompt;
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
import tantalum.value.model.TokenUsage;
import tantalum.value.model.ToolCallBlock;

/**
 * 评测用的脚本化模型：记下每次调用收到的全部输入（{@link Call#input}），按调用的种类回话。第一层评测只看“该进模型的记忆进没进输入”，
 * 不看模型怎么答，所以回话越简单越好：对话回一句“好的。”，记笔记按 {@link #capture} 给的要点，压缩回一段固定的摘要。
 */
final class EvalModel implements ModelClient {

    enum Kind { CHAT, CAPTURE, COMPACT, OTHER }

    /** 一次调用：种类、system、最后一条用户消息的文字、整个输入（system、工具、消息，record 的 toString）。 */
    record Call(Kind kind, String system, String lastUser, String input) {

        /** 记笔记调用里这一轮的摘录（{@code <turn>…</turn>} 之间）；不含会话标题，标题是第一轮的话。 */
        String turn() {
            int start = lastUser.indexOf("<turn>");
            int end = lastUser.lastIndexOf("</turn>");
            return start < 0 || end < start ? "" : lastUser.substring(start, end);
        }
    }

    /** 一轮对话里脚本化的下一步：要么调一个工具，要么回一段话。 */
    record Step(String tool, Map<String, String> arguments, String text) {
        static Step say(String text) {
            return new Step(null, Map.of(), text);
        }

        static Step call(String tool, Map<String, String> arguments) {
            return new Step(tool, arguments, null);
        }
    }

    static final String SUMMARY = "（之前的对话摘要：用户和助手聊了一些杂事。）";

    /** 记笔记的模型写的一条：种类（preference、fact、decision、task、commitment、waiting、note）、要点、原话。 */
    record Note(String kind, String text, String quote) {}

    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private volatile Function<Call, Step> chat = call -> Step.say("好的。");
    private volatile Function<Call, List<Note>> capture = call -> List.of();
    private volatile boolean captureDown;
    private volatile long tokensPerCall;
    private int ids;

    @Override
    public synchronized ModelOutcome call(Prompt prompt, ChunkListener chunks) {
        Call call = new Call(kind(prompt), prompt.system(), lastUser(prompt), prompt.toString());
        calls.add(call);
        // 按 3 字节一个 token 报输入（和压缩测试的 bytes-per-token=3 一致）：小窗口的服务器上对话长了就真的压缩
        long estimate = call.input().getBytes(java.nio.charset.StandardCharsets.UTF_8).length / 3;
        TokenUsage usage = new TokenUsage(Math.max(tokensPerCall, estimate), 10, 0, 0);
        return switch (call.kind()) {
            case CAPTURE -> {
                if (captureDown) {
                    yield new ModelOutcome.Failed(FailureReason.CONNECTION_ERROR, true, null, null, "the note model is down (eval)");
                }
                List<Note> notes = capture.apply(call);
                yield replied(notes.isEmpty() ? NotePrompt.NOTHING
                        : String.join("\n", notes.stream().map(n -> "- [" + n.kind() + "] " + n.text() + " «" + n.quote() + "»").toList()), usage);
            }
            case COMPACT -> replied(summary(call.input()), usage);
            case OTHER -> replied(call.system().startsWith(REWRITE) ? rewrite(call.lastUser()) : call.lastUser(), usage);
            case CHAT -> {
                Step step = chat.apply(call);
                if (step.tool() == null) {
                    yield replied(step.text(), usage);
                }
                Map<String, Json> arguments = new LinkedHashMap<>();
                step.arguments().forEach((key, value) -> arguments.put(key, new Json.Str(value)));
                String raw = EvalSeed.JSON.writeValueAsString(step.arguments());
                List<ContentBlock> blocks = List.of(new ToolCallBlock("eval-" + (++ids), step.tool(), raw, new Json.Obj(arguments)));
                yield new ModelOutcome.Replied(blocks, StopReason.TOOL_USE, "eval", usage);
            }
        };
    }

    /** 找回前改写问题的调用（RecallWork 的 system 开头）。 */
    static final String REWRITE = "Rewrite the user's latest message";

    /** 本机模型改写时会给的中英日关键词，这里按词表给（第一层只测管道，翻得好不好是第二层的事）。 */
    private static final Map<String, String> KEYWORDS = Map.of("cluster", "集群 クラスタ", "production", "生产环境 本番", "database", "数据库",
            "language", "语言", "meeting", "周会", "plate", "车牌", "decide", "定了");

    static String rewrite(String latest) {
        String asked = latest.substring(latest.lastIndexOf("Latest:") < 0 ? 0 : latest.lastIndexOf("Latest:") + 7).strip();
        StringBuilder words = new StringBuilder();
        KEYWORDS.forEach((english, other) -> {
            if (asked.toLowerCase(java.util.Locale.ROOT).contains(english)) {
                words.append(' ').append(english).append(' ').append(other);
            }
        });
        return asked + " |" + words;
    }

    /** 压缩的摘要：照主窗口的说明，把列过的选项（带编号的那句）留在“对话状态”里。 */
    static String summary(String input) {
        java.util.regex.Matcher listed = java.util.regex.Pattern.compile("[^\\n\\]\\[]*1\\. [^\\n\\]\\[]*2\\. [^\\n\\]\\[]*").matcher(input);
        String options = null;
        while (listed.find()) {
            options = listed.group().strip();
        }
        return options == null ? SUMMARY : SUMMARY + "\n列过的选项：" + options;
    }

    private static ModelOutcome replied(String text, TokenUsage usage) {
        return new ModelOutcome.Replied(List.of(new TextBlock(text)), StopReason.END_TURN, "eval", usage);
    }

    private static Kind kind(Prompt prompt) {
        if (prompt.system().equals(NotePrompt.system())) {
            return Kind.CAPTURE;
        }
        if (!prompt.output().toolsAllowed() && lastUser(prompt).startsWith(COMPACTION)) {
            return Kind.COMPACT;
        }
        // 没有工具的调用（以后的改写问题之类）：回用户的原话
        return prompt.tools().isEmpty() ? Kind.OTHER : Kind.CHAT;
    }

    /** 压缩调用的最后一条用户消息的开头（kernel 的 SummaryPrompt）。 */
    private static final String COMPACTION = "The conversation above is about to be replaced by a summary";

    private static String lastUser(Prompt prompt) {
        for (int i = prompt.messages().size() - 1; i >= 0; i--) {
            Message message = prompt.messages().get(i);
            if (message.role() == tantalum.value.model.Role.USER) {
                StringBuilder text = new StringBuilder();
                for (ContentBlock block : message.content()) {
                    if (block instanceof TextBlock textBlock) {
                        text.append(textBlock.text());
                    }
                }
                if (!text.isEmpty()) {
                    return text.toString();
                }
            }
        }
        return "";
    }

    /** 从第 {@code from} 次调用往后、某一种的调用。 */
    List<Call> since(int from, Kind kind) {
        List<Call> found = new ArrayList<>();
        for (int i = from; i < calls.size(); i++) {
            if (calls.get(i).kind() == kind) {
                found.add(calls.get(i));
            }
        }
        return found;
    }

    int count() {
        return calls.size();
    }

    void chat(Function<Call, Step> script) {
        this.chat = script;
    }

    void capture(Function<Call, List<Note>> script) {
        this.capture = script;
    }

    void captureDown(boolean down) {
        this.captureDown = down;
    }

    /** 每次调用至少报这么多输入 token（让压缩早点发生）。 */
    void tokensPerCall(long tokens) {
        this.tokensPerCall = tokens;
    }

    /** 每道题开始前回到默认的回话。 */
    void reset() {
        chat = call -> Step.say("好的。");
        capture = call -> List.of();
        captureDown = false;
        tokensPerCall = 0;
    }
}
