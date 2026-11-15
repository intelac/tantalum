package tantalum.shell.memory.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.memory.DailyNotes;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.ModelOutcome;
import tantalum.shell.trace.RunRow;
import tantalum.shell.trace.Tally;
import tantalum.shell.trace.TraceRecord;
import tantalum.shell.trace.TraceRecord.Head;
import tantalum.shell.trace.TraceRecord.ModelRequest;
import tantalum.shell.trace.TraceRecord.RunHead;
import tantalum.shell.trace.TraceRecord.UserInput;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;
import tantalum.value.model.FailureReason;
import tantalum.value.model.ModelChoice;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tantalum.value.model.TokenUsage;
import tantalum.value.state.EndReason;

/** memory-capture 需求 1、2、4、5：什么时候记、记一次、没值得记的不写、密钥不记、开关、排队、重试。 */
class NoteTakerTest {

    private static final Principal YI = new Principal("yi");
    private static final SessionId S1 = new SessionId("s-1");
    private static final Instant END = Instant.parse("2026-11-15T07:25:00Z");
    private static final ZoneId ZONE = ZoneOffset.UTC;

    @TempDir
    Path dir;

    private final List<String> prompts = new CopyOnWriteArrayList<>();
    private volatile String reply = "- 用户在做 Tantalum 的记忆功能\n- 下一步：每晚整理";

    private final ModelClient model = (prompt, chunks) -> {
        prompts.add(prompt.messages().getLast().content().getFirst() instanceof TextBlock text ? text.text() : "");
        return new ModelOutcome.Replied(List.of(new TextBlock(reply)), StopReason.END_TURN, "qwen", new TokenUsage(100, 20, 0, 0));
    };

    private static RunRow row(String run, EndReason reason, RunHead.Trigger trigger) {
        Tally tally = new Tally(TokenUsage.ZERO, TokenUsage.ZERO, null, 1, 1, 0, null, null, 0, 0, null, List.of(), 0, 0, List.of(), 1L);
        return new RunRow(YI, S1, new RunId(run), 1, trigger, END.minusSeconds(5), END, reason, "p", tally);
    }

    private static List<TraceRecord> records(String run, String said) {
        RunId id = new RunId(run);
        Head head = new Head("u-" + run, "5.0", id, 1, 1, null, END, END);
        Head answer = new Head("m-" + run, "6.0", id, 1, 1, null, END, END);
        return List.of(new UserInput(head, UserInput.Mode.PROMPT, said, TraceRecord.Source.USER),
                new ModelRequest(answer, 1, ModelRequest.Purpose.NORMAL, ModelRequest.Status.DONE, List.of(), 3, "local", "qwen", null, 0,
                        null, null, "好的，先做记忆。", null, null, null, null, List.of()));
    }

    private NoteTaker taker(boolean userWants, ModelClient client, CaptureSettings settings) {
        DailyNotes notes = new DailyNotes(dir);
        return new NoteTaker(settings, user -> userWants, user -> notes, (user, session, run) -> records(run.value(), "把记忆往前提"),
                session -> "记忆计划", new NoteTaker.Writer(client, new ModelChoice("local", "qwen", null)), Clock.systemUTC(), ZONE,
                new long[] {1, 1});
    }

    private static CaptureSettings on() {
        return new CaptureSettings(true, null, null, null, null, 1, 3);
    }

    @Test
    void aCompletedRunIsNotedOnceInTodaysNote() {
        NoteTaker taker = taker(true, model, on());
        assertTrue(taker.note(row("s-1_5", EndReason.COMPLETED, RunHead.Trigger.PROMPT)));
        assertFalse(taker.note(row("s-1_5", EndReason.COMPLETED, RunHead.Trigger.PROMPT)), "the same run is noted once");
        DailyNotes.Note note = new DailyNotes(dir).read("2026-11-15", "s-1").orElseThrow();
        assertEquals("记忆计划", note.title());
        assertEquals("- 用户在做 Tantalum 的记忆功能\n- 下一步：每晚整理", note.sections().getFirst().body());
        assertEquals("s-1_5", note.sections().getFirst().run());
        assertTrue(note.sections().getFirst().heading().startsWith("07:24"));
        assertEquals(1, prompts.size(), "the second time does not even call the model");
        assertTrue(prompts.getFirst().contains("User: 把记忆往前提") && prompts.getFirst().contains("Assistant: 好的，先做记忆。"));
    }

    @Test
    void onlyCompletedPromptRunsAreQueued() {
        assertTrue(NoteTaker.worthNoting(row("r", EndReason.COMPLETED, RunHead.Trigger.FOLLOW_UP)));
        assertFalse(NoteTaker.worthNoting(row("r", EndReason.FAILED, RunHead.Trigger.PROMPT)));
        assertFalse(NoteTaker.worthNoting(row("r", EndReason.CANCELLED, RunHead.Trigger.PROMPT)));
        assertFalse(NoteTaker.worthNoting(row("r", EndReason.STOPPED, RunHead.Trigger.PROMPT)));
        assertFalse(NoteTaker.worthNoting(row("r", EndReason.COMPLETED, RunHead.Trigger.MANUAL_COMPACTION)));
        RunRow open = new RunRow(YI, S1, new RunId("r"), 1, RunHead.Trigger.PROMPT, END, null, null, "p", row("r", null, null).tally());
        assertFalse(NoteTaker.worthNoting(open));
    }

    @Test
    void nothingWorthKeepingAndCredentialBulletsAreNotWritten() {
        NoteTaker taker = taker(true, model, on());
        reply = "NOTHING";
        assertFalse(taker.note(row("s-1_5", EndReason.COMPLETED, RunHead.Trigger.PROMPT)));
        assertTrue(new DailyNotes(dir).read("2026-11-15", "s-1").isEmpty());
        String key = "sk-" + "ant-" + "api03-" + "B".repeat(40);
        reply = "- 用户的 key 是 " + key + "\n- 用户喜欢简体中文";
        assertTrue(taker.note(row("s-1_6", EndReason.COMPLETED, RunHead.Trigger.PROMPT)));
        String body = new DailyNotes(dir).read("2026-11-15", "s-1").orElseThrow().sections().getFirst().body();
        assertEquals("- 用户喜欢简体中文", body);
    }

    @Test
    void theUserSwitchAndTheServerSwitchAreHonored() {
        assertFalse(taker(false, model, on()).note(row("s-1_5", EndReason.COMPLETED, RunHead.Trigger.PROMPT)));
        NoteTaker off = taker(true, model, new CaptureSettings(false, null, null, null, null, 1, 3));
        off.offer(row("s-1_7", EndReason.COMPLETED, RunHead.Trigger.PROMPT));
        assertEquals(0, off.pending());
        assertTrue(prompts.isEmpty());
    }

    @Test
    void aFullQueueDropsTheOldestAndAFailingModelIsRetriedThenGivenUp() throws Exception {
        AtomicBoolean hold = new AtomicBoolean(true);
        AtomicInteger calls = new AtomicInteger();
        ModelClient failing = (prompt, chunks) -> {
            calls.incrementAndGet();
            while (hold.get()) {
                Thread.onSpinWait();
            }
            return new ModelOutcome.Failed(FailureReason.RATE_LIMITED, true, null, "", "429");
        };
        NoteTaker taker = taker(true, failing, on());
        List<RunRow> rows = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            rows.add(row("s-1_" + (10 + i), EndReason.COMPLETED, RunHead.Trigger.PROMPT));
        }
        rows.forEach(taker::offer);
        for (int i = 0; i < 200 && calls.get() == 0; i++) {
            Thread.sleep(5);
        }
        assertTrue(taker.pending() <= 3, "the queue holds at most 3; older ones were dropped");
        hold.set(false);
        assertFalse(taker.note(row("s-1_30", EndReason.COMPLETED, RunHead.Trigger.PROMPT)), "gives up after the retries");
        taker.close();
        assertTrue(calls.get() >= 3, "tried three times: " + calls.get());
    }
}
