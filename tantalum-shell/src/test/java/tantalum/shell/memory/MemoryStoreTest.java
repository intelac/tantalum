package tantalum.shell.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.memory.MemoryStore.WriteResult;

class MemoryStoreTest {

    @TempDir
    Path temp;

    private Instant now = Instant.parse("2026-11-01T03:00:00Z");
    private final Clock clock = new Clock() {
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    };

    private MemoryStore store(int maxEntries, int maxIndexBytes, int maxEntryBytes) {
        return new MemoryStore(temp.resolve("alice/memory"), new MemoryLimits(maxEntries, maxIndexBytes, maxEntryBytes), clock);
    }

    private MemoryStore store() {
        return store(200, 16 * 1024, 8 * 1024);
    }

    @Test
    void firstWriteCreatesTheDirectoryEntryAndIndex() throws Exception {
        MemoryStore store = store();
        assertEquals("", store.indexText());
        assertEquals(List.of(), store.list());

        WriteResult result = store.write("java-version", "user", "回答一律用 Java 25", "按 Java 25 写示例。");

        MemoryEntry entry = assertInstanceOf(WriteResult.Created.class, result).entry();
        assertEquals(now, entry.updatedAt());
        assertTrue(Files.isRegularFile(temp.resolve("alice/memory/java-version.md")));
        assertEquals("- [java-version](java-version.md) — user — 回答一律用 Java 25\n", store.indexText());
        assertEquals(List.of(entry), store.list());
        assertEquals(entry, store.read("java-version").orElseThrow());
        assertEquals("rwx------", java.nio.file.attribute.PosixFilePermissions.toString(
                Files.getPosixFilePermissions(temp.resolve("alice/memory"))));
    }

    @Test
    void sameNameOverwritesAndKeepsTheCount() {
        MemoryStore store = store();
        store.write("a", "user", "first", "1");
        now = now.plusSeconds(60);
        WriteResult second = store.write("a", "project", "second", "2");
        assertInstanceOf(WriteResult.Updated.class, second);
        assertEquals(1, store.list().size());
        MemoryEntry entry = store.read("a").orElseThrow();
        assertEquals(MemoryType.PROJECT, entry.type());
        assertEquals("2", entry.content());
        assertEquals("- [a](a.md) — project — second\n", store.indexText());
    }

    @Test
    void deleteRemovesFileAndIndexLine() {
        MemoryStore store = store();
        store.write("a", "user", "d", "1");
        store.write("b", "user", "d", "2");
        assertTrue(store.delete("a"));
        assertFalse(Files.exists(temp.resolve("alice/memory/a.md")));
        assertEquals("- [b](b.md) — user — d\n", store.indexText());
        assertFalse(store.delete("a"));
        assertFalse(store.delete("Not Valid"));
    }

    @Test
    void listIsNewestFirst() {
        MemoryStore store = store();
        store.write("old", "user", "d", "1");
        now = now.plusSeconds(10);
        store.write("new", "user", "d", "2");
        assertEquals(List.of("new", "old"), store.list().stream().map(MemoryEntry::name).toList());
    }

    @Test
    void entryCountLimitRejectsNewButAllowsOverwrite() {
        MemoryStore store = store(2, 16 * 1024, 8 * 1024);
        store.write("a", "user", "d", "1");
        store.write("b", "user", "d", "2");
        WriteResult.Rejected full = assertInstanceOf(WriteResult.Rejected.class, store.write("c", "user", "d", "3"));
        assertTrue(full.reason().contains("2/2"), full.reason());
        assertTrue(full.reason().contains("memory_delete"));
        assertFalse(Files.exists(temp.resolve("alice/memory/c.md")));
        assertInstanceOf(WriteResult.Updated.class, store.write("a", "user", "d", "changed"));
    }

    @Test
    void indexByteLimitIsEnforced() {
        MemoryStore store = store(200, 80, 8 * 1024);
        assertInstanceOf(WriteResult.Created.class, store.write("a", "user", "short", "1"));
        WriteResult.Rejected rejected = assertInstanceOf(WriteResult.Rejected.class,
                store.write("b", "user", "a description long enough to push the index over eighty bytes", "2"));
        assertTrue(rejected.reason().contains("index"), rejected.reason());
        assertEquals(1, store.list().size());
    }

    @Test
    void entryByteLimitAndValidationAndCredentialsAreRejectedWithoutWriting() {
        MemoryStore store = store(200, 16 * 1024, 10);
        assertTrue(assertInstanceOf(WriteResult.Rejected.class, store.write("a", "user", "d", "x".repeat(11))).reason().contains("11 bytes"));
        assertTrue(assertInstanceOf(WriteResult.Rejected.class, store.write("Bad", "user", "d", "x")).reason().contains("name"));
        assertTrue(assertInstanceOf(WriteResult.Rejected.class, store.write("a", "video", "d", "x")).reason().contains("type"));
        assertTrue(assertInstanceOf(WriteResult.Rejected.class, store.write("a", "user", "", "x")).reason().contains("description"));
        WriteResult.Rejected secret = assertInstanceOf(WriteResult.Rejected.class, store().write("a", "user", "d", "password=hunter2secret"));
        assertEquals(CredentialGuard.REASON, secret.reason());
        assertFalse(Files.exists(temp.resolve("alice/memory")), "nothing was written, not even the directory");
    }

    @Test
    void unparsableFilesAreSkippedAndRebuiltIndexDropsThem() throws Exception {
        MemoryStore store = store();
        store.write("good", "user", "d", "1");
        Files.writeString(temp.resolve("alice/memory/broken.md"), "not a memory file");
        Files.writeString(temp.resolve("alice/memory/notes.txt"), "ignored");
        Files.writeString(temp.resolve("alice/memory/MEMORY.md"), "- [good](good.md) — user — d\ngarbage line\n");
        assertEquals(List.of("good"), store.list().stream().map(MemoryEntry::name).toList());
        assertTrue(store.read("broken").isEmpty());
        store.write("other", "user", "d", "2");
        assertEquals("- [good](good.md) — user — d\n- [other](other.md) — user — d\n", store.indexText());
    }

    @Test
    void concurrentWritesLeaveEveryFileWholeAndTheIndexConsistent() throws Exception {
        MemoryStore store = store();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            String name = "entry-" + (i % 8);
            String body = ("body " + i + " ").repeat(200);
            threads.add(Thread.ofVirtual().start(() -> assertFalse(store.write(name, "project", "d" + body.length(), body) instanceof WriteResult.Rejected)));
        }
        for (Thread thread : threads) {
            thread.join();
        }
        List<MemoryEntry> entries = store.list();
        assertEquals(8, entries.size());
        for (MemoryEntry entry : entries) {
            // 每个文件要么是某次完整的写入，不能是两次写入的拼接或半截。
            assertTrue(entry.content().matches("(body \\d+ )+"), entry.name());
            assertEquals("d" + entry.content().length(), entry.description(), entry.name());
        }
        assertEquals(MemoryFormat.formatIndex(entries), store.indexText());
        try (Stream<Path> files = Files.list(temp.resolve("alice/memory"))) {
            assertTrue(files.noneMatch(file -> file.getFileName().toString().endsWith(".tmp")));
        }
    }
}
