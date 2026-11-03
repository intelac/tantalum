package tantalum.shell.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.plugin.Workspace;

class WritePathsTest {

    @TempDir
    Path root;

    @Test
    void memoryIsOffLimitsInAnyCase() throws Exception {
        Files.createDirectory(root.resolve("memory"));
        Workspace workspace = new Workspace(root);
        for (String path : new String[] {"memory/x.md", "Memory/x.md", "./memory/new/x.md", "memory"}) {
            String message = assertThrows(IllegalArgumentException.class, () -> WritePaths.resolve(workspace, path)).getMessage();
            assertTrue(message.contains("memory_write"), message);
        }
    }

    @Test
    void namesThatOnlyStartWithMemoryAreFine() throws Exception {
        Workspace workspace = new Workspace(root);
        assertEquals(root.toRealPath().resolve("memory-notes.md"), WritePaths.resolve(workspace, "memory-notes.md"));
        assertEquals(root.toRealPath().resolve("docs/memory/x.md"), WritePaths.resolve(workspace, "docs/memory/x.md"));
    }
}
