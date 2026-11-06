package tantalum.shell.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.local.fs.PrivateFiles;
import tantalum.shell.plugin.Workspace;
import tantalum.value.id.Principal;

class WorkspacesTest {

    @TempDir
    Path root;

    @Test
    void eachUserGetsAPrivateDirectory() throws Exception {
        Workspaces workspaces = Workspaces.perUser(root);
        Path alice = workspaces.of(new Principal("alice")).root();
        assertEquals(root.toRealPath().resolve("alice"), alice);
        assertEquals(root.toRealPath().resolve("bob"), workspaces.of(new Principal("bob")).root());
        assertSame(workspaces.of(new Principal("alice")), workspaces.of(new Principal("alice")));
        assertFalse(workspaces.isShared());
        assumeTrue(PrivateFiles.posix(alice), "Windows 上权限靠继承的 ACL，见 WindowsFilesTest");
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(alice)));
    }

    @Test
    void oneUserCannotReachAnother() throws Exception {
        Workspaces workspaces = Workspaces.perUser(root);
        Path alice = workspaces.of(new Principal("alice")).root();
        Files.writeString(alice.resolve("secret.txt"), "secret");
        Workspace bob = workspaces.of(new Principal("bob"));
        assertThrows(IllegalArgumentException.class, () -> bob.resolve("../alice/secret.txt"));
        assertThrows(IllegalArgumentException.class, () -> bob.resolve(alice.resolve("secret.txt").toString()));
    }

    @Test
    void unsafeNamesAreRejected() {
        Workspaces workspaces = Workspaces.perUser(root);
        for (String name : new String[] {"..", "a/b", "A", "a b", "a.b", ""}) {
            assertFalse(Principal.isUsable(name), name);
        }
        assertFalse(Principal.isUsable(null));
        assertThrows(IllegalArgumentException.class, () -> workspaces.of(new Principal("..")));
        assertThrows(IllegalArgumentException.class, () -> workspaces.of(new Principal("a/b")));
        assertTrue(Principal.isUsable("alice_1-x"));
    }

    /** 用户目录被换成指向外面的符号链接：不能把外面的目录当成它的工作区。 */
    @Test
    void userDirectoryLinkingOutsideIsRejected(@TempDir Path elsewhere) throws Exception {
        Files.createSymbolicLink(root.resolve("mallory"), elsewhere);
        Workspaces workspaces = Workspaces.perUser(root);
        assertThrows(IllegalStateException.class, () -> workspaces.of(new Principal("mallory")));
    }

    @Test
    void sharedModeUsesTheRootForEveryone() throws Exception {
        Workspaces workspaces = Workspaces.shared(root);
        assertTrue(workspaces.isShared());
        assertEquals(root.toRealPath(), workspaces.of(new Principal("alice")).root());
        assertEquals(root.toRealPath(), workspaces.of(new Principal("bob")).root());
    }
}
