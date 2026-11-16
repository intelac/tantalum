package tantalum.shell.tool;

import java.util.List;
import tantalum.shell.attachment.AttachmentStore;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.ToolDispatch;

/**
 * 服务器内置的工具：读本人附件的 {@code read_attachment}，问用户的 {@code ask_user}（只有定义，由内核处理），待办清单 {@code todo_write}。读写文件、Git、命令行都在用户自己的电脑上执行
 * （转发工具见 {@code tantalum.shell.local.LocalTool}）；记忆工具另在 {@code MemoryTools}。
 */
public final class BuiltinTools {

    private BuiltinTools() {}

    /** 这些工具的调度描述，不用打开附件库。 */
    public static List<ToolDispatch> dispatch() {
        return List.of(ReadAttachmentTool.DISPATCH, AskUserTool.DISPATCH, TodoWriteTool.DISPATCH, DelegateTool.DISPATCH, WorkspaceAddTool.DISPATCH);
    }

    public static List<Tool> of(AttachmentStore attachments) {
        return List.of(new ReadAttachmentTool(attachments), new AskUserTool(), new TodoWriteTool(), new DelegateTool());
    }

    /** 加上登记工作区的 {@code workspace_add}（要工作区的表和到用户电脑的连接）。 */
    public static List<Tool> of(AttachmentStore attachments, tantalum.shell.project.Projects projects, tantalum.shell.local.LocalLinks links) {
        List<Tool> tools = new java.util.ArrayList<>(of(attachments));
        tools.add(new WorkspaceAddTool(projects, links));
        return List.copyOf(tools);
    }
}
