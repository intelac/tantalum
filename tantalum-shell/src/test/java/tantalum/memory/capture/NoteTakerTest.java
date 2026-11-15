package tantalum.memory.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.memory.DailyNotes;
import tantalum.shell.plugin.RunFinishedListener.FinishedRun;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;

/** memory-capture 需求 1、2、4、5：什么时候记、记一次、没值得记的不写、密钥不记、开关、排队、重试；摘录来自平台（memory-plugin）。 */
class NoteTakerTest {

    private static final Principal YI = new Principal("yi");
    private static final SessionId S1 = new SessionId("s-1");
    private static final Instant END = Instant.parse("2026-11-15T07:25:00Z");
    private static final ZoneId ZONE = ZoneOffset.UTC;
    private static final String EXCERPT = "User: 把记忆往前提\n\nAssistant: 好的，先做记忆。";

    @TempDir
    Path dir;

    private final List<String> prompts = new CopyOnWriteArrayList<>();
    private volatile String reply = "- 用户在做 Tantalum 的记忆功能\n- 下一步：每晚整理";

    private final NoteTaker.Writer model = (system, user, maxTokens) -> {
        prompts.add(user);
        return Optional.of(reply);
    };

    private static FinishedRun run(String run, String reason, boolean manual) {
        return new FinishedRun(YI, S1, new RunId(run), 1, reason, manual, END.minusSeconds(5), END, "记忆计划", EXCERPT);
    }

    private NoteTaker taker(boolean userWants, NoteTaker.Writer writer, CaptureSettings settings) {
        DailyNotes notes = new DailyNotes(dir);
        return new NoteTaker(settings, user -> userWants, user -> notes, writer, ZONE, new long[] {1, 1});
    }

    private static CaptureSettings on() {
        return new CaptureSettings(true, null, null, null, null, 1, 3);
    }

    @Test
    void aCompletedRunIsNotedOnceInTodaysNote() {
        NoteTaker taker = taker(true, model, on());
        assertTrue(taker.note(run("s-1_5", "COMPLETED", false)));
        assertFalse(taker.note(run("s-1_5", "COMPLETED", false)), "the same run is noted once");
        DailyNotes.Note note = new DailyNotes(dir).read("2026-11-15", "s-1").orElseThrow();
        assertEquals("记忆计划", note.title());
        assertEquals("- 用户在做 Tantalum 的记忆功能\n- 下一步：每晚整理", note.sections().getFirst().body());
        assertEquals("s-1_5", note.sections().getFirst().run());
        assertTrue(note.sections().getFirst().heading().startsWith("07:24"));
        assertEquals(1, prompts.size(), "the second time does not even call the model");
        assertTrue(prompts.getFirst().contains("<turn>\n" + EXCERPT + "\n</turn>"), prompts.getFirst());
    }

    @Test
    void onlyCompletedRunsThatAreNotCompactionsAreQueued() {
        assertTrue(NoteTaker.worthNoting(run("r", "COMPLETED", false)));
        assertFalse(NoteTaker.worthNoting(run("r", "FAILED", false)));
        assertFalse(NoteTaker.worthNoting(run("r", "CANCELLED", false)));
        assertFalse(NoteTaker.worthNoting(run("r", "STOPPED", false)));
        assertFalse(NoteTaker.worthNoting(run("r", "COMPLETED", true)));
        assertFalse(NoteTaker.worthNoting(new FinishedRun(YI, S1, new RunId("r"), 1, null, false, END, null, "", "")));
    }

    @Test
    void nothingWorthKeepingAndCredentialBulletsAreNotWritten() {
        NoteTaker taker = taker(true, model, on());
        reply = "NOTHING";
        assertFalse(taker.note(run("s-1_5", "COMPLETED", false)));
        assertTrue(new DailyNotes(dir).read("2026-11-15", "s-1").isEmpty());
        String key = "sk-" + "ant-" + "api03-" + "B".repeat(40);
        reply = "- 用户的 key 是 " + key + "\n- 用户喜欢简体中文";
        assertTrue(taker.note(run("s-1_6", "COMPLETED", false)));
        String body = new DailyNotes(dir).read("2026-11-15", "s-1").orElseThrow().sections().getFirst().body();
        assertEquals("- 用户喜欢简体中文", body);
    }

    @Test
    void theUserSwitchAndTheServerSwitchAreHonored() {
        assertFalse(taker(false, model, on()).note(run("s-1_5", "COMPLETED", false)));
        NoteTaker off = taker(true, model, new CaptureSettings(false, null, null, null, null, 1, 3));
        off.runFinished(run("s-1_7", "COMPLETED", false));
        assertEquals(0, off.pending());
        assertTrue(prompts.isEmpty());
    }

    @Test
    void aLongExcerptKeepsItsHeadAndTail() {
        String cut = NoteTaker.capped("a".repeat(50) + "b".repeat(50), 30);
        assertTrue(cut.startsWith("a".repeat(20)) && cut.endsWith("b".repeat(10)) && cut.contains("…"), cut);
        assertEquals("short", NoteTaker.capped("short", 30));
    }

    @Test
    void aFullQueueDropsTheOldestAndAFailingModelIsRetriedThenGivenUp() throws Exception {
        AtomicBoolean hold = new AtomicBoolean(true);
        AtomicInteger calls = new AtomicInteger();
        NoteTaker.Writer failing = (system, user, maxTokens) -> {
            calls.incrementAndGet();
            while (hold.get()) {
                Thread.onSpinWait();
            }
            return Optional.empty();
        };
        NoteTaker taker = taker(true, failing, on());
        List<FinishedRun> runs = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            runs.add(run("s-1_" + (10 + i), "COMPLETED", false));
        }
        runs.forEach(taker::runFinished);
        for (int i = 0; i < 200 && calls.get() == 0; i++) {
            Thread.sleep(5);
        }
        assertTrue(taker.pending() <= 3, "the queue holds at most 3; older ones were dropped");
        hold.set(false);
        assertFalse(taker.note(run("s-1_30", "COMPLETED", false)), "gives up after the retries");
        taker.close();
        assertTrue(calls.get() >= 3, "tried three times: " + calls.get());
    }
}
