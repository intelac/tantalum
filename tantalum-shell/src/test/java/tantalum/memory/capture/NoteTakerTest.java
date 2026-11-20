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
import tantalum.memory.MemoryLimits;
import tantalum.memory.MemoryStore;
import tantalum.memory.WorkingMemory;
import tantalum.shell.plugin.RunFinishedListener.FinishedRun;
import tantalum.shell.plugin.RunSegment;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;
import tantalum.memory.DailyNotesFixture;
import tantalum.shell.plugin.RunFinishedListenerFixture;

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
    private volatile String reply = "- [note] 用户在做 Tantalum 的记忆功能 «先做记忆»\n- [task] 把记忆往前提 «把记忆往前提»";

    private final NoteTaker.Writer model = (user, system, text, maxTokens) -> {
        prompts.add(text);
        return Optional.of(reply);
    };

    private static final List<RunSegment> SEGMENTS = List.of(new RunSegment(RunSegment.Role.USER, null, null, "把记忆往前提", "1.0"),
            new RunSegment(RunSegment.Role.ASSISTANT, null, null, "好的，先做记忆。", "2.0"));

    private static FinishedRun run(String run, String reason, boolean manual) {
        return new FinishedRun(YI, S1, new RunId(run), 1, reason, manual, END.minusSeconds(5), END, "记忆计划", EXCERPT, SEGMENTS, false);
    }

    private NoteTaker taker(boolean userWants, NoteTaker.Writer writer, CaptureSettings settings) {
        return taker(userWants, writer, settings, NoteTaker.Catchup.NONE);
    }

    private NoteTaker taker(boolean userWants, NoteTaker.Writer writer, CaptureSettings settings, NoteTaker.Catchup catchup) {
        tantalum.memory.ledger.Ledger ledger = tantalum.memory.ledger.Ledger.open(dir, java.time.Clock.systemUTC());
        DailyNotes notes = new DailyNotes(ledger);
        NoteFiler filer = new NoteFiler(new MemoryStore(ledger, MemoryLimits.defaults()), new WorkingMemory(ledger), notes,
                tantalum.shell.plugin.SensitiveText.current());
        return NoteTakerFixture.of(settings, user -> userWants, user -> notes, user -> filer, writer, catchup, ZONE, new long[] {1, 1});
    }

    private static CaptureSettings on() {
        return new CaptureSettings(true, null, null, null, null, 1, 3);
    }

    @Test
    void aCompletedRunIsNotedOnceInTodaysNote() {
        NoteTaker taker = taker(true, model, on());
        assertTrue(taker.note(run("s-1_5", "COMPLETED", false)));
        assertFalse(taker.note(run("s-1_5", "COMPLETED", false)), "the same run is noted once");
        DailyNotes.Note note = DailyNotesFixture.of(dir).read("2026-11-15", "s-1").orElseThrow();
        assertEquals("记忆计划", note.title());
        assertTrue(note.sections().getFirst().body().endsWith("- 用户在做 Tantalum 的记忆功能\n- 把记忆往前提"), note.sections().getFirst().body());
        assertTrue(note.sections().getFirst().body().startsWith("> "), "the card opens with what the user said, word for word");
        assertEquals("s-1_5", note.sections().getFirst().run());
        assertTrue(note.sections().getFirst().heading().startsWith("07:24"));
        assertEquals(1, prompts.size(), "the second time does not even call the model");
        assertTrue(prompts.getFirst().contains("<turn>\n" + EXCERPT + "\n</turn>"), prompts.getFirst());
        assertEquals(List.of("把记忆往前提"), new WorkingMemory(DailyNotesFixture.of(dir).ledger()).open().stream().map(item -> item.text()).toList(),
                "only the task in the user's own words goes into working memory; the inferred note stays in the day's card");
    }

    /** 当天卡按这个人的时区分天：UTC 早上 7 点，在洛杉矶还是前一天晚上。 */
    @Test
    void theDayFollowsTheUsersTimeZone() {
        tantalum.memory.ledger.Ledger ledger = tantalum.memory.ledger.Ledger.open(dir, java.time.Clock.systemUTC());
        DailyNotes notes = new DailyNotes(ledger);
        NoteFiler filer = new NoteFiler(new MemoryStore(ledger, MemoryLimits.defaults()), new WorkingMemory(ledger), notes,
                tantalum.shell.plugin.SensitiveText.current());
        NoteTaker taker = new NoteTaker(on(), user -> true, user -> notes, user -> filer, model, NoteTaker.Catchup.NONE,
                (tantalum.value.id.Principal user) -> ZoneId.of("America/Los_Angeles"));
        assertTrue(taker.note(run("s-1_5", "COMPLETED", false)));
        assertTrue(DailyNotesFixture.of(dir).read("2026-11-15", "s-1").isEmpty());
        DailyNotes.Note note = DailyNotesFixture.of(dir).read("2026-11-14", "s-1").orElseThrow();
        assertTrue(note.sections().getFirst().heading().startsWith("23:24"), note.sections().getFirst().heading());
        taker.close();
    }

    @Test
    void onlyCompletedRunsThatAreNotCompactionsAreQueued() {
        assertTrue(NoteTaker.worthNoting(run("r", "COMPLETED", false)));
        assertFalse(NoteTaker.worthNoting(run("r", "FAILED", false)));
        assertFalse(NoteTaker.worthNoting(run("r", "CANCELLED", false)));
        assertFalse(NoteTaker.worthNoting(run("r", "STOPPED", false)));
        assertFalse(NoteTaker.worthNoting(run("r", "COMPLETED", true)));
        assertFalse(NoteTaker.worthNoting(RunFinishedListenerFixture.finishedRun(YI, S1, new RunId("r"), 1, null, false, END, null, "", "")));
        NoteTaker taker = taker(true, model, on());
        taker.runFinished(new FinishedRun(YI, S1, new RunId("r"), 1, "COMPLETED", false, END, END, "", "", SEGMENTS, true));
        assertEquals(0, taker.pending(), "an off-the-record run is not queued");
        taker.close();
    }

    @Test
    void nothingWorthKeepingAndCredentialBulletsAreNotWritten() {
        NoteTaker taker = taker(true, model, on());
        reply = "NOTHING";
        assertFalse(taker.note(run("s-1_5", "COMPLETED", false)));
        assertTrue(DailyNotesFixture.of(dir).read("2026-11-15", "s-1").orElseThrow().sections().getFirst().body().lines()
                .allMatch(line -> line.startsWith("> ")), "nothing was noted; the card only keeps what the user said");
        String key = "sk-" + "ant-" + "api03-" + "B".repeat(40);
        reply = "- [fact] 用户的 key 是 " + key + " «把记忆往前提»\n- [note] 用户想先做记忆 «先做记忆»\n- [fact] 用户喜欢 Vim «喜欢 Vim»";
        assertTrue(taker.note(run("s-1_6", "COMPLETED", false)));
        String body = DailyNotesFixture.of(dir).read("2026-11-15", "s-1").orElseThrow().sections().getLast().body();
        assertEquals("- 用户想先做记忆", body.lines().filter(line -> !line.startsWith("> ")).collect(java.util.stream.Collectors.joining("\n")),
                "the key and the unquoted claim are dropped");
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
        NoteTaker.Writer failing = (user, system, text, maxTokens) -> {
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

    /**
     * 关掉要等手上那一轮做完：模型调用结束时平台还要记一笔用量，服务器停下后再写就写到了已经关掉的库里
     * （{@code OffRecordApiTest} 偶尔删不掉临时目录就是这个）。
     */
    @Test
    void closeWaitsForTheNoteInHand() throws Exception {
        java.util.concurrent.CountDownLatch calling = new java.util.concurrent.CountDownLatch(1);
        AtomicBoolean recorded = new AtomicBoolean();
        NoteTaker.Writer slow = (user, system, text, maxTokens) -> {
            calling.countDown();
            long until = System.nanoTime() + 300_000_000L;
            while (System.nanoTime() < until) {
                Thread.onSpinWait();   // 一次不理会中断的模型调用
            }
            recorded.set(true);        // 调用结束时记用量
            return Optional.of(reply);
        };
        NoteTaker taker = taker(true, slow, on());
        taker.runFinished(run("s-1_40", "COMPLETED", false));
        assertTrue(calling.await(5, java.util.concurrent.TimeUnit.SECONDS), "the worker picked the run up");
        taker.close();
        assertTrue(recorded.get(), "close returned while the model call was still running");
    }
}
