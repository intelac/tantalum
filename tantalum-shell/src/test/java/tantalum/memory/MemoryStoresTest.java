package tantalum.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.local.fs.PrivateFiles;
import tantalum.shell.plugin.Workspace;
import tantalum.shell.tool.Workspaces;
import tantalum.value.id.Principal;

class MemoryStoresTest {

    private static final Principal ALICE = new Principal("alice");
    private static final Principal BOB = new Principal("bob");

    @TempDir
    Path temp;

    /** 和服务器一样：账在插件的私有状态目录，旧格式目录按插件数据目录的规矩（memory-plugin、memory-final-plan M1）。 */
    static MemoryStores stores(Workspaces workspaces, Path data, MemoryLimits limits) {
        return new MemoryStores(user -> tantalum.shell.boot.PlatformServices.stateDir(data, MemoryTools.PLUGIN, user),
                user -> tantalum.shell.boot.PlatformServices.dataDir(workspaces, data, MemoryStores.DIR, user), limits, Clock.systemUTC());
    }

    /** M1：两种工作区模式下，账都在数据目录下的私有目录里，不在任何人的工作区里。 */
    @Test
    void theLedgerLivesInThePrivateStateDirInBothModes() throws Exception {
        for (Workspaces workspaces : List.of(Workspaces.perUser(temp.resolve("ws")), Workspaces.shared(Files.createDirectories(temp.resolve("shared"))))) {
            Path data = Files.createTempDirectory(temp, "data");
            MemoryStores stores = stores(workspaces, data, MemoryLimits.defaults());
            MemoryStore alice = stores.of(ALICE);
            assertSame(alice, stores.of(ALICE));
            alice.write("a", "user", "d", "mine");
            assertEquals(data.resolve("plugins/memory/alice").toAbsolutePath().normalize(), alice.dir());
            assertFalse(alice.dir().startsWith(temp.resolve("ws").toAbsolutePath()));
            assertTrue(stores.of(BOB).list().isEmpty());
            assumeTrue(PrivateFiles.posix(alice.dir()), "Windows 上权限靠继承的 ACL，见 WindowsFilesTest");
            assertEquals("rwx------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(alice.dir())));
        }
    }

    /** M1：旧格式的条目、每天的笔记和删掉笔记的墓碑，第一次打开时导进账；重开不重复导入。 */
    @Test
    void theOldFormatIsImportedOnce() throws Exception {
        Workspaces workspaces = Workspaces.perUser(temp.resolve("ws"));
        Path legacy = Files.createDirectories(workspaces.of(ALICE).root().resolve("memory"));
        Files.writeString(legacy.resolve("java.md"), MemoryFormat.formatEntry(new MemoryEntry("java", MemoryType.USER, "Java 25 only",
                "Always Java 25.", java.time.Instant.parse("2026-11-01T00:00:00Z"))));
        Files.writeString(legacy.resolve("MEMORY.md"), "- [java](java.md) — user — Java 25 only\n");
        Path day = Files.createDirectories(legacy.resolve("daily/2026-11-15"));
        Files.writeString(day.resolve("s-1.md"), "---\nsession: s-1\ntitle: \"记忆\"\nday: 2026-11-15\n---\n\n## 10:00 · #1\n<!-- run:s-1_3 -->\n- 决定先做记忆\n");
        Files.writeString(day.resolve("s-2.deleted"), "s-2_7\n");
        Path data = temp.resolve("data");

        MemoryStores stores = stores(workspaces, data, MemoryLimits.defaults());
        assertEquals("Always Java 25.", stores.of(ALICE).read("java").orElseThrow().content());
        assertEquals("- [java](java.md) — user — Java 25 only\n", stores.of(ALICE).indexText(), "the prompt bytes stay the same");
        DailyNotes notes = stores.notes(ALICE);
        assertEquals("- 决定先做记忆", notes.read("2026-11-15", "s-1").orElseThrow().sections().getFirst().body());
        assertTrue(notes.noted("2026-11-15", "s-2", "s-2_7"), "a run noted before the note was deleted stays noted");
        assertTrue(notes.read("2026-11-15", "s-2").isEmpty());
        assertTrue(stores.ledger(ALICE).hasMarker(tantalum.memory.ledger.LegacyImport.MARKER));

        MemoryStores reopened = stores(workspaces, data, MemoryLimits.defaults());
        assertEquals(1, reopened.of(ALICE).list().size(), "not imported twice");
        assertTrue(Files.exists(legacy.resolve("java.md")), "the old files stay as a backup");
    }

    @Test
    void unsafeNamesAreRejected() {
        MemoryStores stores = stores(Workspaces.perUser(temp), temp.resolve("data"), MemoryLimits.defaults());
        assertThrows(IllegalArgumentException.class, () -> stores.of(new Principal("../x")));
        assertThrows(IllegalArgumentException.class, () -> stores.of(new Principal("Alice")));
    }
}
