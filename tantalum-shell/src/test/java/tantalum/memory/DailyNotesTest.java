package tantalum.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** memory-capture 需求 1.3、1.5、3、5.3：每天的笔记的位置、每轮一段、只记一次、删掉不回来、列表顺序、名字检查。 */
class DailyNotesTest {

    @TempDir
    Path dir;

    @Test
    void eachRunIsOneSectionAndIsWrittenOnce() throws Exception {
        DailyNotes notes = DailyNotesFixture.of(dir);
        assertTrue(notes.append("2026-11-15", "s-1", "读 \"README\"", "s-1_3", "16:25 · 第 1 轮", "- 用户在做 Tantalum\n- 下一步：记忆"));
        assertFalse(notes.append("2026-11-15", "s-1", "ignored", "s-1_3", "16:26", "- again"), "the same run is not noted twice");
        assertTrue(notes.append("2026-11-15", "s-1", "ignored", "s-1_9", "16:40 · 第 2 轮", "- 决定先做记忆"));
        // 笔记记在记忆账上（memory-final-plan M1），不再是 daily/ 下的文件
        assertTrue(Files.isRegularFile(dir.resolve(tantalum.memory.ledger.Ledger.FILE)));
        DailyNotes.Note note = notes.read("2026-11-15", "s-1").orElseThrow();
        assertEquals("读 'README'", note.title());
        assertEquals(List.of("s-1_3", "s-1_9"), note.sections().stream().map(DailyNotes.Section::run).toList());
        assertEquals("16:25 · 第 1 轮", note.sections().getFirst().heading());
        assertEquals("- 用户在做 Tantalum\n- 下一步：记忆", note.sections().getFirst().body());
        assertTrue(notes.noted("2026-11-15", "s-1", "s-1_9"));
    }

    @Test
    void deletedAndEditedNotesDoNotComeBackOnReplay() {
        DailyNotes notes = DailyNotesFixture.of(dir);
        notes.append("2026-11-15", "s-1", "t", "s-1_3", "h", "- a");
        assertTrue(notes.write("2026-11-15", "s-1", "---\ntitle: t\n---\n\n## h\n- edited, marker removed\n"));
        assertFalse(notes.append("2026-11-15", "s-1", "t", "s-1_3", "h", "- a"), "an edit that drops the marker still remembers the run");
        assertTrue(notes.delete("2026-11-15", "s-1"));
        assertTrue(notes.read("2026-11-15", "s-1").isEmpty());
        assertFalse(notes.append("2026-11-15", "s-1", "t", "s-1_3", "h", "- a"), "a deleted run is not written back");
        assertTrue(notes.append("2026-11-15", "s-1", "t", "s-1_4", "h", "- later runs still are"));
        assertFalse(notes.delete("2026-11-14", "s-1"));
    }

    @Test
    void theListIsNewestDayFirstWithPreviews() throws Exception {
        DailyNotes notes = DailyNotesFixture.of(dir);
        notes.append("2026-11-14", "s-a", "older", "s-a_1", "h", "- old");
        notes.append("2026-11-15", "s-b", "newer", "s-b_1", "h", "- first");
        notes.append("2026-11-15", "s-b", "newer", "s-b_2", "h", "- " + "x".repeat(300));
        List<DailyNotes.Day> days = notes.list(10);
        assertEquals(List.of("2026-11-15", "2026-11-14"), days.stream().map(DailyNotes.Day::day).toList());
        DailyNotes.Listed listed = days.getFirst().notes().getFirst();
        assertEquals("newer", listed.title());
        assertEquals(2, listed.sections());
        assertTrue(listed.preview().endsWith("…") && listed.preview().length() <= DailyNotes.PREVIEW_CHARS + 1);
        assertEquals(1, notes.list(1).size());
        assertEquals(List.of(), DailyNotesFixture.of(dir.resolve("nobody")).list(5));
    }

    @Test
    void badDaysSessionsAndRunsAreRefused() {
        DailyNotes notes = DailyNotesFixture.of(dir);
        assertThrows(IllegalArgumentException.class, () -> notes.read("../etc", "s-1"));
        assertThrows(IllegalArgumentException.class, () -> notes.read("2026-11-15", "../../x"));
        assertThrows(IllegalArgumentException.class, () -> notes.append("2026-11-15", "s-1", "t", "bad run -->", "h", "b"));
    }
}
