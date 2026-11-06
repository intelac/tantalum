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
        List<String> valid = damaged.lines().filter(MemoryFormat::isIndexLine).toList();
        assertEquals(List.of(
                "- [cobol-rules](cobol-rules.md) — project — COBOL 迁移约束",
                "- [java-version](java-version.md) — user — 回答一律用 Java 25 语法"), valid);
        assertEquals(false, MemoryFormat.isIndexLine(""));
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

    /** windows-server 需求 3.3：记忆名加 .md 是文件名，Windows 设备名不行，拒绝的原因单独写明。 */
    @Test
    void windowsDeviceNamesAreRejectedWithTheirOwnReason() {
        for (String reserved : new String[] {"nul", "con", "com1", "lpt0"}) {
            assertFalse(MemoryFormat.isValidName(reserved), reserved);
            MemoryStore.WriteResult.Rejected rejected = MemoryFormat.validate(reserved, "user", "d", "c", 100).orElseThrow();
            assertEquals(MemoryStore.Problem.RESERVED_NAME, rejected.problem());
            assertEquals(reserved + " is a reserved device name on Windows; choose another name", rejected.reason());
        }
        assertTrue(MemoryFormat.isValidName("nul-notes"));
        assertEquals(MemoryStore.Problem.INVALID_NAME, MemoryFormat.validate("NUL", "user", "d", "c", 100).orElseThrow().problem());
    }

    @Test
    void validationExplainsEachRule() {
        assertTrue(MemoryFormat.validate("ok", "user", "d", "c", 100).isEmpty());
        assertTrue(MemoryFormat.validate("Bad", "user", "d", "c", 100).orElseThrow().reason().contains("name"));
        assertTrue(MemoryFormat.validate("ok", "video", "d", "c", 100).orElseThrow().reason().contains("type"));
        assertTrue(MemoryFormat.validate("ok", "user", "  ", "c", 100).orElseThrow().reason().contains("description"));
        assertTrue(MemoryFormat.validate("ok", "user", "two\nlines", "c", 100).orElseThrow().reason().contains("single line"));
        assertTrue(MemoryFormat.validate("ok", "user", "d".repeat(201), "c", 100).orElseThrow().reason().contains("200"));
        assertTrue(MemoryFormat.validate("ok", "user", "d", null, 100).orElseThrow().reason().contains("content"));
        // 字节按 UTF-8 算：一个汉字三字节。
        assertTrue(MemoryFormat.validate("ok", "user", "d", "汉字汉字", 11).orElseThrow().reason().contains("12 bytes"));
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
