package tantalum.shell.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.plugin.Tool;

class BuiltinToolsListTest {

    @TempDir
    Path root;

    private static List<String> names(List<Tool> tools) {
        return tools.stream().map(Tool::name).toList();
    }

    @Test
    void eachUserGetsTheWriteTools() {
        assertEquals(List.of("read_file", "grep", "list_dir", "find_files", "write_file", "edit_file"),
                names(BuiltinTools.of(Workspaces.perUser(root), null, new Observations())));
    }

    /** C11：共用工作目录时不给写工具。 */
    @Test
    void aSharedWorkspaceHasNoWriteTools() {
        assertEquals(List.of("read_file", "grep", "list_dir", "find_files"),
                names(BuiltinTools.of(Workspaces.shared(root), null, new Observations())));
    }
}
