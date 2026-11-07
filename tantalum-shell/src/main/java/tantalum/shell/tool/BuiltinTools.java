package tantalum.shell.tool;

import java.util.List;
import tantalum.shell.attachment.AttachmentStore;
import tantalum.shell.plugin.Tool;

/**
 * 服务器内置的工具：读本人附件的 {@code read_attachment}，和问用户的 {@code ask_user}（waiting-state，只有定义，由内核处理）。读写文件、Git、命令行都在用户自己的电脑上执行
 * （local-workspace L6，转发工具见 {@code tantalum.shell.local.LocalTool}）；记忆工具另在 {@code MemoryTools}。
 */
public final class BuiltinTools {

    private BuiltinTools() {}

    public static List<Tool> of(AttachmentStore attachments) {
        return List.of(new ReadAttachmentTool(attachments), new AskUserTool());
    }
}
