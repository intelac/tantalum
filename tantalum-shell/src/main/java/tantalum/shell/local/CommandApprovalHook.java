package tantalum.shell.local;

import java.util.List;
import java.util.Map;
import tantalum.kernel.hook.KernelHook;
import tantalum.shell.plugin.PluginInfo;
import tantalum.value.hook.Allow;
import tantalum.value.hook.Ask;
import tantalum.value.hook.HookContext;
import tantalum.value.hook.HookResult;
import tantalum.value.hook.Stop;
import tantalum.value.json.Json;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.question.Answer;
import tantalum.value.question.Question;
import tantalum.value.state.KernelState;
import tantalum.value.state.SessionState;
import tantalum.value.text.LocalText;

/**
 * 命令先问（connector-jar 需求 5）：模型要在用户电脑上跑 {@code bash} 或 {@code powershell} 时，先在对话页上问用户，等多久都行。
 * 三个选项：允许、这个会话都允许、拒绝。连接程序没有沙箱，命令以用户的身份运行，这是唯一的一道关。
 *
 * <p>照 {@code tantalum.examples.ToolApprovalHook} 写，只管这两个工具：“这个会话都允许”记成会话记录 {@code approval} 下的
 * {@code shell: true}，两个工具共用一键，换一台电脑也不用重新允许。允许名单只来自记录，钩子是纯函数，恢复和重放结果相同。
 * 拒绝时告诉模型不要换个办法绕过去（同 DSH {@code application-access.ts:299-301}）。
 *
 * <p>内置、默认开；用户在插件页“本机工作区 → 命令”里关掉（“执行前先问我”）就不问。开着时出错按拦截处理。
 */
public final class CommandApprovalHook implements KernelHook, PluginInfo {

    public static final String NAME = "commandApproval";
    /** 会话记录的键，同例子插件的审批，两者的记录放在一起。 */
    public static final String NOTE = "approval";
    /** 会话记录里的字段：这个会话的命令都允许了。 */
    static final String SHELL = "shell";
    static final String QUESTION_PREFIX = "command-";
    static final String ONCE = "once";
    static final String SESSION = "session";
    static final String DENY = "deny";
    static final List<String> TOOLS = List.of("bash", "powershell");
    private static final int SHOWN_COMMAND_CHARS = 2000;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public LocalText description() {
        return LocalText.en("Before a command runs on your computer (bash or PowerShell), shows it on the conversation page and waits: "
                        + "allow, allow all commands in this session, or deny. Commands run as you, without a sandbox.")
                .zh("在你的电脑上跑命令（bash 或 PowerShell）之前，先在对话页上给你看这条命令并等你决定：允许、这个会话都允许，或者拒绝。"
                        + "命令以你的身份运行，没有沙箱。")
                .ja("コンピューターでコマンド（bash または PowerShell）を実行する前に、会話ページにそのコマンドを表示して判断を待ちます。"
                        + "許可、このセッションでは常に許可、拒否から選べます。コマンドはあなたとして実行され、サンドボックスはありません。");
    }

    /** 事先告诉模型（QAgent 问题 10）。 */
    @Override
    public String modelNote() {
        return "Each bash or powershell command on the user's computer waits for the user to approve it; the user sees the full command. "
                + "Run one clear command at a time, and if a command is denied, do not work around it: ask the user instead.";
    }

    /** 默认开。 */
    @Override
    public boolean defaultEnabled() {
        return true;
    }

    @Override
    public boolean failClosed() {
        return true;
    }

    @Override
    public HookResult beforeToolCall(KernelState state, HookContext context) {
        ToolCallBlock call = context.call();
        if (call == null || !TOOLS.contains(call.name()) || allowedInSession(state, context)) {
            return Allow.PLAIN;
        }
        return new Ask(question(call));
    }

    /**
     * “这个会话都允许”只在追到头是你引起的那一轮里认（session-model 需求 6.6）：外面的事引起的那一轮，每条命令照样问。
     */
    private static boolean allowedInSession(KernelState state, HookContext context) {
        SessionState session = state.sessions().get(context.run().session());
        tantalum.value.state.RunState run = state.runs().get(context.run().run());
        boolean yours = run != null && run.cause().root() == tantalum.value.event.Root.USER;
        return yours && session != null && session.note(NOTE).fields().get(SHELL) instanceof Json.Bool allowed && allowed.value();
    }

    /** 同一个调用再问就是同一个问题。正文是命令全文（太长截断），有 {@code workdir} 时另起一行写目录。 */
    static Question question(ToolCallBlock call) {
        String command = string(call.arguments(), "command");
        String workdir = string(call.arguments(), "workdir");
        StringBuilder body = new StringBuilder(cut(command == null ? "" : command));
        LocalText title = LocalText.en("Run this command on your computer?").zh("在你的电脑上执行这条命令吗？").ja("コンピューターでこのコマンドを実行しますか？");
        LocalText detail;
        if (workdir != null && !workdir.isBlank()) {
            detail = LocalText.en(body + "\n\nDirectory: " + workdir).zh(body + "\n\n目录：" + workdir).ja(body + "\n\nディレクトリ：" + workdir);
        } else {
            detail = LocalText.of(body.toString());
        }
        return new Question(QUESTION_PREFIX + call.id(), title, detail, List.of(
                new Question.Choice(ONCE, LocalText.en("Allow").zh("允许").ja("許可")),
                new Question.Choice(SESSION, LocalText.en("Allow all in this session").zh("这个会话都允许").ja("このセッションではすべて許可")),
                new Question.Choice(DENY, LocalText.en("Deny").zh("拒绝").ja("拒否"))));
    }

    @Override
    public HookResult onAnswer(KernelState state, HookContext context) {
        Question question = context.question();
        if (question == null || !question.id().startsWith(QUESTION_PREFIX)) {
            return Allow.PLAIN;
        }
        Answer answer = context.answer();
        if (answer == null) {
            return new Stop("no decision about running this command before the deadline");
        }
        return switch (answer.choice()) {
            case ONCE -> Allow.PLAIN;
            case SESSION -> Allow.PLAIN.withNote(NOTE, new Json.Obj(Map.of(SHELL, new Json.Bool(true))));
            default -> new Stop("The user denied running this command. Do not retry it or run it another way without asking.");
        };
    }

    private static String string(Json arguments, String name) {
        return arguments instanceof Json.Obj object && object.fields().get(name) instanceof Json.Str text ? text.value() : null;
    }

    private static String cut(String text) {
        if (text.length() <= SHOWN_COMMAND_CHARS) {
            return text;
        }
        int at = Character.isHighSurrogate(text.charAt(SHOWN_COMMAND_CHARS - 1)) ? SHOWN_COMMAND_CHARS - 1 : SHOWN_COMMAND_CHARS;
        return text.substring(0, at) + "…";
    }
}
