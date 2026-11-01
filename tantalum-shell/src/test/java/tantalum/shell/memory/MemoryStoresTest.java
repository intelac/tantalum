package tantalum.shell.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.plugin.Workspace;
import tantalum.shell.tool.Workspaces;
import tantalum.value.id.Principal;

class MemoryStoresTest {

    private static final Principal ALICE = new Principal("alice");
    private static final Principal BOB = new Principal("bob");

    @TempDir
    Path temp;

    @Test
    void perUserModeKeepsMemoryInsideTheUsersWorkspace() throws Exception {
        Workspaces workspaces = Workspaces.perUser(temp.resolve("ws"));
        MemoryStores stores = new MemoryStores(workspaces, temp.resolve("data"), MemoryLimits.defaults(), Clock.systemUTC());
        MemoryStore alice = stores.of(ALICE);
        assertEquals(workspaces.of(ALICE).root().resolve("memory"), alice.dir());
        assertSame(alice, stores.of(ALICE));
        alice.write("a", "user", "d", "mine");
        // 自己的工具能读到自己的记忆文件，别人的到不了。
        assertEquals(alice.dir().resolve("a.md"), workspaces.of(ALICE).resolve("memory/a.md"));
        assertThrows(IllegalArgumentException.class, () -> workspaces.of(BOB).resolve("../alice/memory/a.md"));
        assertEquals("rwx------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(alice.dir())));
    }

    @Test
    void sharedModeKeepsMemoryOutOfTheSharedWorkspace() throws Exception {
        Path root = Files.createDirectories(temp.resolve("shared"));
        Workspaces workspaces = Workspaces.shared(root);
        MemoryStores stores = new MemoryStores(workspaces, temp.resolve("data"), MemoryLimits.defaults(), Clock.systemUTC());
        MemoryStore alice = stores.of(ALICE);
        alice.write("a", "user", "d", "mine");
        assertEquals(temp.resolve("data/memory/alice").toAbsolutePath().normalize(), alice.dir());
        assertFalse(alice.dir().startsWith(root.toRealPath()));
        Workspace shared = workspaces.of(BOB);
        assertThrows(IllegalArgumentException.class, () -> shared.resolve(alice.dir().resolve("a.md").toString()));
        assertThrows(IllegalArgumentException.class, () -> shared.resolve("../data/memory/alice/a.md"));
        assertTrue(stores.of(BOB).list().isEmpty());
    }

    @Test
    void unsafeNamesAreRejected() {
        MemoryStores stores = new MemoryStores(Workspaces.perUser(temp), temp.resolve("data"), MemoryLimits.defaults(), Clock.systemUTC());
        assertThrows(IllegalArgumentException.class, () -> stores.of(new Principal("../x")));
        assertThrows(IllegalArgumentException.class, () -> stores.of(new Principal("Alice")));
    }
}
