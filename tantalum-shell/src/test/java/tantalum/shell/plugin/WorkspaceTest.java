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

    /** 目录外存在的文件和不存在的文件必须报同一句话，否则工具调用方能探测服务器上的文件布局。 */
    @Test
    void outsideAndMissingPathsAreIndistinguishable() {
        String existing = assertThrows(IllegalArgumentException.class, () -> workspace.resolve("../secret.txt")).getMessage();
        String missing = assertThrows(IllegalArgumentException.class, () -> workspace.resolve("../nope.txt")).getMessage();
        String inside = assertThrows(IllegalArgumentException.class, () -> workspace.resolve("nope.txt")).getMessage();
        assertEquals(existing.replace("secret", "X"), missing.replace("nope", "X"));
        assertEquals(missing.replace("../", ""), inside);
        org.junit.jupiter.api.Assertions.assertFalse(existing.contains(temp.toString()));
    }

    @Test
    void writeResolvesNewFilesAndNewDirectories() throws Exception {
        Path real = root.toRealPath();
        assertEquals(real.resolve("new.txt"), workspace.resolveForWrite("new.txt"));
        assertEquals(real.resolve("a/b/c.txt"), workspace.resolveForWrite("a/b/c.txt"));
        assertEquals(real.resolve("inside.txt"), workspace.resolveForWrite("./inside.txt"));
    }

    @Test
    void writeRejectsEscapesAndTheRootItself() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> workspace.resolveForWrite("../new.txt"));
        assertThrows(IllegalArgumentException.class, () -> workspace.resolveForWrite("a/../../new.txt"));
        assertThrows(IllegalArgumentException.class, () -> workspace.resolveForWrite(temp.resolve("new.txt").toString()));
        assertThrows(IllegalArgumentException.class, () -> workspace.resolveForWrite("."));
    }

    @Test
    void writeRejectsSymlinkedDirectoriesLeadingOutside() throws Exception {
        Files.createSymbolicLink(root.resolve("out"), temp);
        assertThrows(IllegalArgumentException.class, () -> workspace.resolveForWrite("out/new.txt"));
        assertThrows(IllegalArgumentException.class, () -> workspace.resolveForWrite("out/deeper/new.txt"));
        Files.createSymbolicLink(root.resolve("dangling"), temp.resolve("gone"));
        assertThrows(IllegalArgumentException.class, () -> workspace.resolveForWrite("dangling"));
    }

    @Test
    void writeFollowsSymlinksThatStayInside() throws Exception {
        Files.createDirectory(root.resolve("real"));
        Files.createSymbolicLink(root.resolve("alias"), root.resolve("real"));
        assertEquals(root.toRealPath().resolve("real/new.txt"), workspace.resolveForWrite("alias/new.txt"));
    }

    @Test
    void writeRejectsAFileUsedAsADirectory() {
        assertThrows(IllegalArgumentException.class, () -> workspace.resolveForWrite("inside.txt/new.txt"));
    }
}
