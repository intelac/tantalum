package tantalum.shell.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MemoryFormatTest {

    private static final Instant AT = Instant.parse("2026-11-01T02:10:00Z");
    private static final MemoryEntry JAVA = new MemoryEntry("java-version", MemoryType.USER, "回答一律用 Java 25 语法",
            "用户 2026-11-01 明确要求：示例代码按 Java 25。\n\n- 不提旧写法\n", AT);

    @Test
    void entryRoundTripsThroughTheFileFormat() {
        String text = MemoryFormat.formatEntry(JAVA);
        assertTrue(text.startsWith("---\nname: java-version\ndescription: 回答一律用 Java 25 语法\ntype: user\nupdated: 2026-11-01T02:10:00Z\n---\n\n"), text);
        assertEquals(Optional.of(JAVA), MemoryFormat.parseEntry(text));
        assertEquals(Optional.of(JAVA), MemoryFormat.parseEntry(text.replace("\n", "\r\n")));
    }

    @Test
    void brokenFrontmatterIsRejectedNotThrown() {
        assertTrue(MemoryFormat.parseEntry(null).isEmpty());
        assertTrue(MemoryFormat.parseEntry("no fence at all").isEmpty());
        assertTrue(MemoryFormat.parseEntry("---\nname: x\n").isEmpty(), "unterminated fence");
        assertTrue(MemoryFormat.parseEntry("---\nname: x\ntype: user\ndescription: d\n---\n\nbody").isEmpty(), "missing updated");
        assertTrue(MemoryFormat.parseEntry("---\nname: x\ntype: video\ndescription: d\nupdated: 2026-11-01T02:10:00Z\n---\n\n").isEmpty(), "unknown type");
        assertTrue(MemoryFormat.parseEntry("---\nname: Bad Name\ntype: user\ndescription: d\nupdated: 2026-11-01T02:10:00Z\n---\n\n").isEmpty(), "bad name");
        assertTrue(MemoryFormat.parseEntry("---\nname: x\ntype: user\ndescription: d\nupdated: yesterday\n---\n\n").isEmpty(), "bad time");
        assertTrue(MemoryFormat.parseEntry("---\nnocolon\n---\n\n").isEmpty(), "line without colon");
    }

    @Test
    void indexLinesRoundTripAndBadLinesAreSkipped() {
        MemoryEntry other = new MemoryEntry("cobol-rules", MemoryType.PROJECT, "COBOL 迁移约束", "x", AT);
        String index = MemoryFormat.formatIndex(List.of(JAVA, other));
        assertEquals("- [cobol-rules](cobol-rules.md) — project — COBOL 迁移约束\n"
                + "- [java-version](java-version.md) — user — 回答一律用 Java 25 语法\n", index);

        String damaged = index + "random prose someone typed\n- [Mismatch](other.md) — user — x\n- [weird](weird.md) — video — x\n";
        List<MemoryFormat.IndexLine> parsed = MemoryFormat.parseIndex(damaged);
        assertEquals(List.of(
                new MemoryFormat.IndexLine("cobol-rules", MemoryType.PROJECT, "COBOL 迁移约束"),
                new MemoryFormat.IndexLine("java-version", MemoryType.USER, "回答一律用 Java 25 语法")), parsed);
        assertEquals(List.of(), MemoryFormat.parseIndex(null));
        assertEquals(List.of(), MemoryFormat.parseIndex(""));
    }

    @Test
    void namesAreStrictlyLimited() {
        assertTrue(MemoryFormat.isValidName("a"));
        assertTrue(MemoryFormat.isValidName("java-version-25"));
        assertTrue(MemoryFormat.isValidName("x".repeat(64)));
        for (String bad : new String[] {null, "", "x".repeat(65), "Upper", "has space", "has_underscore", "..", "a/b", "a.md", "名字"}) {
            assertFalse(MemoryFormat.isValidName(bad), String.valueOf(bad));
        }
    }

    @Test
    void validationExplainsEachRule() {
        assertTrue(MemoryFormat.validate("ok", "user", "d", "c", 100).isEmpty());
        assertTrue(MemoryFormat.validate("Bad", "user", "d", "c", 100).orElseThrow().contains("name"));
        assertTrue(MemoryFormat.validate("ok", "video", "d", "c", 100).orElseThrow().contains("type"));
        assertTrue(MemoryFormat.validate("ok", "user", "  ", "c", 100).orElseThrow().contains("description"));
        assertTrue(MemoryFormat.validate("ok", "user", "two\nlines", "c", 100).orElseThrow().contains("single line"));
        assertTrue(MemoryFormat.validate("ok", "user", "d".repeat(201), "c", 100).orElseThrow().contains("200"));
        assertTrue(MemoryFormat.validate("ok", "user", "d", null, 100).orElseThrow().contains("content"));
        // 字节按 UTF-8 算：一个汉字三字节。
        assertTrue(MemoryFormat.validate("ok", "user", "d", "汉字汉字", 11).orElseThrow().contains("12 bytes"));
        assertTrue(MemoryFormat.validate("ok", "user", "d", "汉字汉字", 12).isEmpty());
    }

    @Test
    void typesParseCaseInsensitivelyAndListThemselves() {
        assertEquals(Optional.of(MemoryType.FEEDBACK), MemoryType.parse(" Feedback "));
        assertTrue(MemoryType.parse("nope").isEmpty());
        assertTrue(MemoryType.parse(null).isEmpty());
        assertEquals("user, feedback, project, reference", MemoryType.keys());
    }
}
