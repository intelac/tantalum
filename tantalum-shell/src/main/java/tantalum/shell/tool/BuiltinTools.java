package tantalum.shell.tool;

import java.util.ArrayList;
import java.util.List;
import tantalum.shell.attachment.AttachmentStore;
import tantalum.shell.plugin.Tool;

/** 内置的文件工具。记忆工具另在 {@code MemoryTools}。 */
public final class BuiltinTools {

    private BuiltinTools() {}

    /** 共用工作目录时大家能读彼此的文件，不再提供改别人文件的手段，不给写工具（core-tools C11）。 */
    public static List<Tool> of(Workspaces workspaces, AttachmentStore attachments, Observations observations) {
        List<Tool> tools = new ArrayList<>(List.of(
                new ReadFileTool(attachments, observations), new GrepTool(), new ListDirTool(), new FindFilesTool()));
        if (!workspaces.isShared()) {
            tools.add(new WriteFileTool(observations));
            tools.add(new EditFileTool(observations));
        }
        return List.copyOf(tools);
    }
}
