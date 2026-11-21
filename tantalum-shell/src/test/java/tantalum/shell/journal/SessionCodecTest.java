package tantalum.shell.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.value.assignment.AssignmentOutcome;
import tantalum.value.assignment.Assignments;
import tantalum.value.assignment.OpenAssignment;
import tantalum.value.command.Assign;
import tantalum.value.command.ReturnReport;
import tantalum.value.command.StopAssignment;
import tantalum.value.decision.AssignmentStarted;
import tantalum.value.decision.ReportSettled;
import tantalum.value.decision.SessionChange;
import tantalum.value.decision.SessionChangeFixture;
import tantalum.value.decision.StepRecord;
import tantalum.value.event.Assignment;
import tantalum.value.event.AssignmentCancelled;
import tantalum.value.event.Event;
import tantalum.value.event.Input;
import tantalum.value.event.Report;
import tantalum.value.event.Root;
import tantalum.value.event.SessionCreated;
import tantalum.value.event.SessionRenamed;
import tantalum.value.event.StopRequested;
import tantalum.value.event.UserPrompt;
import tantalum.value.event.UserPromptFixture;
import tantalum.value.id.CommandId;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;
import tantalum.value.id.SubmissionId;
import tantalum.value.model.AssignmentBlock;
import tantalum.value.model.Message;
import tantalum.value.model.ModelChoice;
import tantalum.value.model.ReportBlock;
import tantalum.value.model.Role;
import tantalum.value.state.HelperOf;
import tantalum.value.state.InboxItem;
import tantalum.value.state.SessionKind;

/**
 * session-model 需求 8：会话的创建、对会话说的三种话、收件队列、交代的名单在账里写了能原样读回；
 * 建好不变的四样只写在建它的那一行；没变的不写。（取代 workspaces 的 SessionDirCodecTest：目录不再跟着提问走。）
 */
class SessionCodecTest {

    private static final Principal USER = new Principal("alice");
    private static final SessionId SESSION = new SessionId("s-1");
    private static final SessionId HELPER = new SessionId("s-1-t3-0");
    private static final Instant AT = Instant.parse("2026-11-17T10:00:00Z");
    private static final ModelChoice SONNET = new ModelChoice("claude", "claude-sonnet-5-5", "medium");

    private final EventCodec codec = EventCodec.withBuiltins();
    private final RecordCodec records = new RecordCodec(codec);

    private void readsBack(Event event) {
        assertEquals(event, codec.decode(event.type(), event.version(), codec.encode(event)), event.type());
    }

    private StepRecord readsBack(StepRecord record) {
        String line = records.encode(record, RecordMemo.writer(USER, SESSION));
        assertEquals(record, records.decode(line, RecordMemo.reader(USER, SESSION)).record(), line);
        return record;
    }

    private String line(SessionChange change) {
        return records.encode(new StepRecord(0, change, List.of(), List.of(), null, null, List.of(), List.of()), RecordMemo.writer(USER, SESSION));
    }

    @Test
    void everyNewEventReadsBack() {
        readsBack(new SessionCreated(SessionKind.MAIN, "C:\\APPL\\Quattro", true, null));
        readsBack(new SessionCreated(SessionKind.HELPER, "/home/alice/app", false,
                new HelperOf(SESSION, "t3-0", "reviewer", "You review code.", List.of("read"))));
        readsBack(new SessionCreated(SessionKind.REGULAR, null, false, null));
        readsBack(UserPromptFixture.of(new SubmissionId("s"), new RequestId("r"), "hi", List.of(), SONNET));
        readsBack(UserPromptFixture.join(new SubmissionId("s"), new RequestId("r"), "and this"));
        readsBack(new Assignment("t3-0", SESSION, Root.USER, "Check module A.", SONNET));
        readsBack(new Report("t3-0", AssignmentOutcome.COMPLETED, "fine", Root.OUTSIDE));
        readsBack(new Report("t3-0", AssignmentOutcome.STOPPED, null, Root.USER));
        readsBack(new StopRequested(new RequestId("r"), null));
        readsBack(new StopRequested(new RequestId("r"), "t3-0"));
        readsBack(new AssignmentCancelled("t3-0"));
        readsBack(new SessionRenamed(new SubmissionId("s"), new RequestId("r"), "Quattro review"));
    }

    /** 一条普通的提问一个字不多写：没有目录、没有“不进记忆”，这两样在建会话的事件里。 */
    @Test
    void aPromptCarriesNothingAboutTheSession() {
        String written = codec.encode(UserPromptFixture.of(new SubmissionId("s"), new RequestId("r"), "hi"));
        assertFalse(written.contains("dir") || written.contains("offRecord") || written.contains("owner"), written);
        assertTrue(written.contains("\"delivery\":\"QUEUE\""), written);
        assertEquals(UserPrompt.class, codec.decode(UserPrompt.TYPE, 1, written).getClass());
    }

    /** 建会话的那一行写下种类、出身、目录、不进记忆；读回来是同一个变化。 */
    @Test
    void theCreatingLineCarriesWhatIsFixedThen() {
        HelperOf helper = new HelperOf(SESSION, "t3-0", "reviewer", "You review code.", List.of("read"));
        SessionChange created = new SessionChange(HELPER, USER, true, SessionKind.HELPER, helper, "/home/alice/app", true,
                List.of(Message.assignment("t3-0", "Check module A.")), null, null, null, null, null, null, null, null, null, null, null, null);
        String line = records.encode(new StepRecord(0, created, List.of(), List.of(), null, null, List.of(), List.of()), RecordMemo.writer(USER, HELPER));
        assertTrue(line.contains("\"kind\":\"HELPER\"") && line.contains("\"dir\":\"/home/alice/app\"") && line.contains("\"offRecord\":true"), line);
        assertTrue(line.contains("\"kind\":\"assignment\""), "the assignment is its own block: " + line);
        assertEquals(created, records.decode(line, RecordMemo.reader(USER, HELPER)).record().session());
    }

    /** 之后的行不再写这四样；一个普通的新会话也只写种类。 */
    @Test
    void laterLinesAndPlainSessionsWriteNoMore() {
        String later = line(SessionChangeFixture.of(SESSION, USER, false, List.of(Message.user("hi")), null, null, null, null, null, null, null));
        assertFalse(later.contains("\"kind\":\"REGULAR\"") || later.contains("\"created\"") || later.contains("\"dir\"")
                || later.contains("\"offRecord\"") || later.contains("\"helper\""), later);
        String plain = line(SessionChangeFixture.of(SESSION, USER, true, List.of(), null, null, null, null, null, null, null));
        assertTrue(plain.contains("\"kind\":\"REGULAR\""), plain);
        assertFalse(plain.contains("\"dir\"") || plain.contains("\"offRecord\"") || plain.contains("\"helper\"")
                || plain.contains("\"assignments\"") || plain.contains("\"inbox\""), plain);
    }

    @Test
    void theInboxReadsBack() {
        List<InboxItem> inbox = List.of(
                new InboxItem(4, Input.Kind.PROMPT, Root.USER, Message.user("later"), SONNET, false, null),
                new InboxItem(5, Input.Kind.PROMPT, Root.USER, Message.user("and this"), null, true, null),
                new InboxItem(6, Input.Kind.REPORT, Root.OUTSIDE,
                        new Message(Role.USER, List.of(new ReportBlock("t3-0", AssignmentOutcome.COMPLETED, "fine")), false), null, true, "t3-0"));
        readsBack(new StepRecord(0, SessionChangeFixture.of(SESSION, USER, false, List.of(), null, null, new SessionChange.Changed<>(inbox),
                null, null, null, null), List.of(), List.of(), null, null, List.of(), List.of()));
    }

    /** 交代这一块（subagent 需求 4.5）：还没回来的、没人说话时的两个计数，都写进账、原样读回；命令和事实也是。 */
    @Test
    void theAssignmentsTheirCommandsAndFactsReadBack() {
        RunRef run = new RunRef(USER, SESSION, RunId.of(SESSION, 1));
        Assignments open = new Assignments(List.of(new OpenAssignment("t3-0", HELPER, "reviewer", "check module A", AT, AT.plusSeconds(1800),
                new CommandId("c1"), Root.OUTSIDE)), 2, 3);
        SessionChange change = new SessionChange(SESSION, USER, false, null, null, null, false, List.of(), null, null, null, null, null, null,
                null, null, null, null, new SessionChange.Changed<>(open), null);
        StepRecord record = readsBack(new StepRecord(0, change, List.of(), List.of(), null, null,
                List.of(new Assign(new CommandId("c2"), run, "t3-0", HELPER, "reviewer", "check module A\nand report", "/home/alice/app", false,
                                SONNET, Root.USER),
                        new ReturnReport(new CommandId("c3"), run, SESSION, "t3-0", AssignmentOutcome.COMPLETED, "fine", Root.USER),
                        new StopAssignment(new CommandId("c4"), run, "t3-0", HELPER)),
                List.of(new AssignmentStarted(run, "t3-0", HELPER, "reviewer", "check module A", "call-1"),
                        new ReportSettled(run, "t3-0", AssignmentOutcome.TIMED_OUT, true))));
        assertTrue(records.encode(record, RecordMemo.writer(USER, SESSION)).contains("\"assignments\""));
    }

    /** 帮手会话里交代的内容和交代出去的结果都是自己的内容块。 */
    @Test
    void theTwoNewBlocksReadBack() {
        readsBack(new StepRecord(0, SessionChangeFixture.of(SESSION, USER, false, List.of(Message.assignment("t3-0", "Check module A."),
                new Message(Role.USER, List.of(new ReportBlock("t3-0", AssignmentOutcome.FAILED, null)), false)), null, null, null, null, null,
                null, null), List.of(), List.of(), null, null, List.of(), List.of()));
        assertTrue(line(SessionChangeFixture.of(SESSION, USER, false, List.of(new Message(Role.USER, List.of(new AssignmentBlock("t3-0", "x")),
                false)), null, null, null, null, null, null, null)).contains("\"kind\":\"assignment\""));
    }
}
