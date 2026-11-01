package tantalum.shell.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KnowledgeBaseTest {

    @TempDir
    Path dir;

    @Test
    void filesAreJoinedInNameOrderWithHeadings() throws Exception {
        Files.writeString(dir.resolve("20-style.md"), "Use Java 25.\n");
        Files.writeString(dir.resolve("10-project.md"), "# Project\nCOBOL migration.\n");
        Files.writeString(dir.resolve("ignored.txt"), "not markdown");
        Files.createDirectory(dir.resolve("sub.md"));
        String text = new KnowledgeBase(Optional.of(dir), 64 * 1024).text();
        assertEquals("### 10-project.md\n\n# Project\nCOBOL migration.\n\n### 20-style.md\n\nUse Java 25.", text);
    }

    @Test
    void truncatesByFileOrderWhenOverTheLimit() throws Exception {
        Files.writeString(dir.resolve("a.md"), "x".repeat(40));
        Files.writeString(dir.resolve("b.md"), "y".repeat(40));
        String text = new KnowledgeBase(Optional.of(dir), 70).text();
        assertTrue(text.contains("x".repeat(40)));
        assertFalse(text.contains("yyy"));
        assertTrue(text.contains("truncated"), "the model is told, not only the log");
    }

    @Test
    void missingOrUnconfiguredDirIsEmpty() {
        assertEquals("", new KnowledgeBase(Optional.of(dir.resolve("nope")), 100).text());
        assertEquals("", KnowledgeBase.none().text());
        assertEquals("", new KnowledgeBase(Optional.of(dir), 100).text());
    }

    @Test
    void cachesByModificationTimeAndSeesChanges() throws Exception {
        Path file = dir.resolve("a.md");
        Files.writeString(file, "one");
        Files.setLastModifiedTime(file, FileTime.from(Instant.parse("2026-01-01T00:00:00Z")));
        KnowledgeBase base = new KnowledgeBase(Optional.of(dir), 1000);
        assertEquals("### a.md\n\none", base.text());
        Files.writeString(file, "two");
        Files.setLastModifiedTime(file, FileTime.from(Instant.parse("2026-01-02T00:00:00Z")));
        assertEquals("### a.md\n\ntwo", base.text());
        Files.writeString(dir.resolve("b.md"), "three");
        Files.setLastModifiedTime(dir.resolve("b.md"), FileTime.from(Instant.parse("2026-01-02T00:00:00Z")));
        assertTrue(base.text().contains("three"), "a new file with the same stamp is still picked up by count");
    }
}
