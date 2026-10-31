package tantalum.shell.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceTest {

    @TempDir
    Path temp;

    private Path root;
    private Workspace workspace;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createDirectory(temp.resolve("root"));
        Files.writeString(root.resolve("inside.txt"), "ok");
        Files.writeString(temp.resolve("secret.txt"), "secret");
        workspace = new Workspace(root);
    }

    @Test
    void resolvesAPathInside() throws Exception {
        assertEquals(root.toRealPath().resolve("inside.txt"), workspace.resolve("inside.txt"));
        assertEquals(root.toRealPath(), workspace.resolve("."));
    }

    @Test
    void rejectsDotDot() {
        assertThrows(IllegalArgumentException.class, () -> workspace.resolve("../secret.txt"));
        assertThrows(IllegalArgumentException.class, () -> workspace.resolve("a/../../secret.txt"));
    }

    @Test
    void rejectsAbsolutePathOutside() {
        assertThrows(IllegalArgumentException.class, () -> workspace.resolve(temp.resolve("secret.txt").toString()));
    }

    @Test
    void rejectsSymlinkLeadingOutside() throws Exception {
        Files.createSymbolicLink(root.resolve("link.txt"), temp.resolve("secret.txt"));
        assertThrows(IllegalArgumentException.class, () -> workspace.resolve("link.txt"));
    }

    @Test
    void rejectsSymlinkedDirectoryLeadingOutside() throws Exception {
        Files.createSymbolicLink(root.resolve("dir"), temp);
        assertThrows(IllegalArgumentException.class, () -> workspace.resolve("dir/secret.txt"));
    }

    @Test
    void rejectsMissingFile() {
        assertThrows(IllegalArgumentException.class, () -> workspace.resolve("missing.txt"));
    }
}
