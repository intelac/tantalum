package tantalum.shell.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.shell.trace.TraceRecord;
import tantalum.shell.trace.TraceRecord.Head;
import tantalum.shell.trace.TraceRecord.Source;
import tantalum.shell.trace.TraceRecord.UserInput;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;

/** 按运行从新到旧切页：至少 20 个运行或 400 条记录，满足其一就停，至少一个运行；{@code before} 游标；按运行定位。 */
class TracePageTest {

    private static final SessionId SESSION = new SessionId("s");

    /** {@code runs} 个运行，第 i 个从序号 10·i 开始，各有 {@code size} 条记录。顺序打乱，页面要按 pos 排好。 */
    private static List<TraceRecord> records(int runs, int size) {
        List<TraceRecord> records = new ArrayList<>();
        for (int i = 0; i < runs; i++) {
            RunId run = RunId.of(SESSION, 10L * i);
            for (int j = 0; j < size; j++) {
                Head head = new Head("u:" + i + "." + j, (10L * i) + "." + j, run, i + 1, 1, null, Instant.EPOCH, Instant.EPOCH);
                records.add(new UserInput(head, UserInput.Mode.PROMPT, "x", Source.USER));
            }
        }
        Collections.reverse(records);
        return records;
    }

    private static List<Long> runStarts(TracePage page) {
        return page.records().stream().map(record -> record.head().run().seq()).distinct().toList();
    }

    @Test
    void latestPageTakesTwentyRunsAndPointsAtTheOldestOneTaken() {
        TracePage page = TracePage.of(999, records(25, 2), null);
        assertEquals(20, runStarts(page).size());
        assertEquals(50L, page.before());
        assertTrue(page.hasMore());
        assertEquals(999, page.seq());
        assertEquals("50.0", page.records().getFirst().head().pos(), "sorted by pos");

        TracePage older = TracePage.of(999, records(25, 2), page.before());
        assertEquals(List.of(0L, 10L, 20L, 30L, 40L), runStarts(older));
        assertEquals(0L, older.before());
        assertFalse(older.hasMore());
    }

    @Test
    void stopsAtFourHundredRecordsButNeverSplitsARun() {
        TracePage page = TracePage.of(0, records(5, 150), null);
        assertEquals(List.of(20L, 30L, 40L), runStarts(page), "150 + 150 < 400, the third run makes 450");
        assertEquals(450, page.records().size());
        assertTrue(page.hasMore());
    }

    @Test
    void alwaysAtLeastOneRunEvenWhenItIsHuge() {
        TracePage page = TracePage.of(0, records(2, 1000), null);
        assertEquals(List.of(10L), runStarts(page));
        assertEquals(1000, page.records().size());
    }

    @Test
    void nothingBeforeTheFirstRunIsAnEmptyLastPage() {
        TracePage page = TracePage.of(0, records(3, 1), 0L);
        assertTrue(page.records().isEmpty());
        assertNull(page.before());
        assertFalse(page.hasMore());
        assertNull(TracePage.of(-1, List.of(), null).before());
    }

    @Test
    void throughARunReachesBackToIncludeItWhateverTheSize() {
        TracePage page = TracePage.through(0, records(30, 1), RunId.of(SESSION, 30));
        assertEquals(27, runStarts(page).size());
        assertEquals(30L, page.before());
        assertTrue(page.hasMore());
        assertNull(TracePage.through(0, records(3, 1), RunId.of(SESSION, 5)), "no such run");
    }
}
