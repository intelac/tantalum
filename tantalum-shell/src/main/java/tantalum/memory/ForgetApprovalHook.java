package tantalum.memory;

import java.util.ArrayList;
import java.util.List;
import tantalum.kernel.hook.KernelHook;
import tantalum.shell.plugin.PluginInfo;
import tantalum.value.hook.Allow;
import tantalum.value.hook.Ask;
import tantalum.value.hook.HookContext;
import tantalum.value.hook.HookResult;
import tantalum.value.hook.Stop;
import tantalum.value.json.Json;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.Message;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolResultBlock;
import tantalum.value.question.Answer;
import tantalum.value.question.Question;
import tantalum.value.state.KernelState;
import tantalum.value.state.SessionState;
import tantalum.value.text.LocalText;

/**
 * 忘掉之前先问你（2026-11-20 Yi 定）：聊着聊着发现记忆不对，和模型说清楚以后，模型要用 {@code memory_delete} 真的去掉
 * （{@code places} 选中的行，或 {@code name} 整条）时，先在对话页上把要去掉的原文给你看，你点了“忘掉”才动。只是找候选
 * （{@code text}）不问：那一步什么都不改。每次都问，没有“以后都允许”：记忆是你的，去掉了找不回来。
 *
 * <p>照 {@code WorkspaceApprovalHook} 写：钩子是纯函数，不读记忆账。给你看的原文从这个会话里前面那次“找”的工具结果里逐字取
 * （结果里每处一行 {@code - id 编号 (在哪): 原文}）；编号不在前面列出来过的，照实写“没列出来过”，由你决定。
 * 属于记忆插件：记忆开着它就在（插件页上没有单独的开关，也不把记忆变成“必须开启”），出错按拦截处理。
 */
public final class ForgetApprovalHook implements KernelHook, PluginInfo {

    public static final String NAME = "memoryForgetApproval";
    static final String QUESTION_PREFIX = "memory-forget-";
    static final String FORGET = "forget";
    static final String KEEP = "keep";
    static final int SHOWN = 12;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String plugin() {
        return MemoryTools.PLUGIN;
    }

    @Override
    public LocalText description() {
        return LocalText.en("Before the assistant removes anything from your memory or withdraws a line from a past conversation, shows "
                        + "you exactly what would go and waits for you: forget it, or keep it.")
                .zh("助手要从你的记忆里去掉东西、或者撤下过去对话里的话之前，先把要去掉的原文给你看并等你决定：忘掉，或者留着。")
                .ja("アシスタントが記憶から何かを消す前、または過去の会話の発言を取り下げる前に、消える内容をそのまま表示して判断を待ちます。忘れる、残すから選べます。");
    }

    @Override
    public String modelNote() {
        return "memory_delete with places or name waits for the user to confirm; the user sees the exact lines. If the user says keep, "
                + "do not remove them another way: ask what they want instead.";
    }

    @Override
    public boolean failClosed() {
        return true;
    }

    @Override
    public HookResult beforeToolCall(KernelState state, HookContext context) {
        ToolCallBlock call = context.call();
        if (call == null || !MemoryDeleteTool.NAME.equals(call.name())) {
            return Allow.PLAIN;
        }
        String name = string(call.arguments(), "name");
        List<String> places = places(call.arguments());
        if ((name == null || name.isBlank()) && places.isEmpty()) {
            return Allow.PLAIN;
        }
        SessionState session = state.sessions().get(context.run().session());
        return new Ask(question(call, name, places, session == null ? List.of() : session.view()));
    }

    /** 同一个调用再问就是同一个问题。正文是要去掉的每一处的原文。 */
    static Question question(ToolCallBlock call, String name, List<String> places, List<Message> said) {
        StringBuilder detail = new StringBuilder();
        if (name != null && !name.isBlank()) {
            detail.append(name.strip());
        }
        int unlisted = 0;
        List<String> lines = new ArrayList<>();
        for (String place : places) {
            String line = listed(said, place);
            if (line == null) {
                unlisted++;
            } else if (lines.size() < SHOWN) {
                lines.add(line);
            }
        }
        lines.forEach(line -> detail.append(detail.isEmpty() ? "" : "\n").append("- ").append(line));
        int hidden = places.size() - unlisted - lines.size();
        String more = hidden > 0 ? "\n(+" + hidden + ")" : "";
        LocalText body = unlisted == 0 ? LocalText.of(detail + more)
                : LocalText.en(detail + more + (detail.isEmpty() ? "" : "\n") + unlisted + " more place(s) that were not listed in this conversation.")
                        .zh(detail + more + (detail.isEmpty() ? "" : "\n") + "另有 " + unlisted + " 处没有在这个对话里列出来过。")
                        .ja(detail + more + (detail.isEmpty() ? "" : "\n") + "ほかに、この会話で表示されていない箇所が " + unlisted + " 件あります。");
        boolean entry = name != null && !name.isBlank();
        return new Question(QUESTION_PREFIX + call.id(),
                entry ? LocalText.en("Delete this whole memory entry?").zh("把这条记忆整条删掉吗？").ja("この記憶をまるごと削除しますか？")
                        : LocalText.en("Forget these lines?").zh("忘掉这几行吗？").ja("これらの行を忘れますか？"),
                body, List.of(
                        new Question.Choice(FORGET, LocalText.en("Forget").zh("忘掉").ja("忘れる")),
                        new Question.Choice(KEEP, LocalText.en("Keep").zh("留着").ja("残す"))));
    }

    /** 这个会话里前面的工具结果里列出这个编号的那一行（{@code - id 编号 (在哪): 原文}）里编号后面的部分；没列过为 null。 */
    static String listed(List<Message> said, String place) {
        String mark = "- id " + place + " ";
        for (int i = said.size() - 1; i >= 0; i--) {
            for (ContentBlock block : said.get(i).content()) {
                if (block instanceof ToolResultBlock result) {
                    for (String line : result.text().split("\n")) {
                        if (line.startsWith(mark)) {
                            return line.substring(mark.length()).strip();
                        }
                    }
                }
            }
        }
        return null;
    }

    @Override
    public HookResult onAnswer(KernelState state, HookContext context) {
        Question question = context.question();
        if (question == null || !question.id().startsWith(QUESTION_PREFIX)) {
            return Allow.PLAIN;
        }
        Answer answer = context.answer();
        if (answer == null) {
            return new Stop("no decision about forgetting before the deadline; nothing was removed");
        }
        return FORGET.equals(answer.choice()) ? Allow.PLAIN
                : new Stop("The user decided to keep these. Nothing was removed. Do not remove them another way; ask what they want.");
    }

    private static List<String> places(Json arguments) {
        return arguments instanceof Json.Obj given && given.fields().get("places") instanceof Json.Arr list
                ? list.items().stream().filter(Json.Str.class::isInstance).map(each -> ((Json.Str) each).value()).toList() : List.of();
    }

    private static String string(Json arguments, String name) {
        return arguments instanceof Json.Obj object && object.fields().get(name) instanceof Json.Str text ? text.value() : null;
    }
}
