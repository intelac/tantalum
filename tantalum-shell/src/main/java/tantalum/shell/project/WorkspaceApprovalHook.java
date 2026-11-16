package tantalum.shell.project;

import java.util.List;
import tantalum.kernel.hook.KernelHook;
import tantalum.shell.plugin.PluginInfo;
import tantalum.shell.tool.WorkspaceAddTool;
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
import tantalum.value.text.LocalText;

/**
 * 新建工作区先问（2026-11-16 Yi 定）：模型要用 {@code workspace_add} 把一个目录登记成工作区时，先在对话页上给用户看名字和目录，
 * 等多久都行。两个选项：新建、不建。每次都问，没有“以后都允许”：工作区是用户侧栏里长期留着的东西，不该悄悄多出来。
 *
 * <p>照 {@code tantalum.shell.local.CommandApprovalHook} 写：钩子是纯函数，等待是内核里“等用户回答”的状态，重启不丢。
 * 内置、必须开启（插件页上不能关），出错按拦截处理。
 */
public final class WorkspaceApprovalHook implements KernelHook, PluginInfo {

    public static final String NAME = "workspaceApproval";
    static final String QUESTION_PREFIX = "workspace-";
    static final String ADD = "add";
    static final String DENY = "deny";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public LocalText description() {
        return LocalText.en("Before the assistant adds a folder on your computer as a workspace, shows the name and the folder on the "
                        + "conversation page and waits for you: add it, or not.")
                .zh("助手要把你电脑上的一个目录加成工作区之前，先在对话页上给你看名字和目录并等你决定：新建，或者不建。")
                .ja("アシスタントがコンピューター上のフォルダーをワークスペースとして追加する前に、会話ページに名前とフォルダーを表示して判断を待ちます。"
                        + "追加する、追加しないから選べます。");
    }

    @Override
    public String modelNote() {
        return "workspace_add waits for the user to confirm the new workspace; the user sees its name and folder. If the user says no, "
                + "do not add it another way: ask which folder to use instead.";
    }

    @Override
    public boolean mandatory() {
        return true;
    }

    @Override
    public boolean failClosed() {
        return true;
    }

    @Override
    public HookResult beforeToolCall(KernelState state, HookContext context) {
        ToolCallBlock call = context.call();
        if (call == null || !WorkspaceAddTool.NAME.equals(call.name())) {
            return Allow.PLAIN;
        }
        return new Ask(question(call));
    }

    /** 同一个调用再问就是同一个问题。正文是目录，给了名字时另起一行写名字。 */
    static Question question(ToolCallBlock call) {
        String path = string(call.arguments(), "path");
        String title = string(call.arguments(), "title");
        String folder = path == null ? "" : path.strip();
        LocalText detail = title == null || title.isBlank()
                ? LocalText.of(folder)
                : LocalText.en(folder + "\n\nName: " + title.strip()).zh(folder + "\n\n名字：" + title.strip()).ja(folder + "\n\n名前：" + title.strip());
        return new Question(QUESTION_PREFIX + call.id(),
                LocalText.en("Add this folder as a workspace?").zh("把这个目录加成工作区吗？").ja("このフォルダーをワークスペースに追加しますか？"),
                detail, List.of(
                        new Question.Choice(ADD, LocalText.en("Add").zh("新建").ja("追加")),
                        new Question.Choice(DENY, LocalText.en("Don't add").zh("不建").ja("追加しない"))));
    }

    @Override
    public HookResult onAnswer(KernelState state, HookContext context) {
        Question question = context.question();
        if (question == null || !question.id().startsWith(QUESTION_PREFIX)) {
            return Allow.PLAIN;
        }
        Answer answer = context.answer();
        if (answer == null) {
            return new Stop("no decision about adding this workspace before the deadline");
        }
        return ADD.equals(answer.choice()) ? Allow.PLAIN
                : new Stop("The user decided not to add this workspace. Do not add it another way; ask which folder to use.");
    }

    private static String string(Json arguments, String name) {
        return arguments instanceof Json.Obj object && object.fields().get(name) instanceof Json.Str text ? text.value() : null;
    }
}
