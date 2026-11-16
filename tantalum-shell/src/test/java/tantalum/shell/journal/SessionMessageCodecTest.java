package tantalum.shell.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.value.command.Send;
import tantalum.value.decision.SessionChange;
import tantalum.value.decision.StepRecord;
import tantalum.value.event.SessionMessage;
import tantalum.value.id.CommandId;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;
import tantalum.value.state.AwaitingReply;
import tantalum.value.state.Inbox;
import tantalum.value.state.OwnerRef;
import tantalum.value.state.RunState;

/**
 * 会话之间说话用到的东西写进账都能原样读回：会话来话这一种事件、送话这一种命令、等会话回答这个状态、排队的来话、运行的新主人。
 * 和它无关的会话一个字也不多写。
 */
class SessionMessageCodecTest {

    private static final Principal USER = new Principal("alice");
    private static final SessionId A = new SessionId("a");
    private static final SessionId B = new SessionId("s-a_7-0");
    private static final CommandId ID = new CommandId("a_7-0");
    private static final RunRef FROM = new RunRef(USER, A, new RunId("a_5"));

    private final EventCodec codec = EventCodec.withBuiltins();
    private final RecordCodec records = new RecordCodec(codec);

    private static SessionMessage work() {
        return new SessionMessage(ID, FROM, "Task:\ncount the files", null, false, 1, null, "/home/alice/app", null);
    }

    @Test
    void theEventReadsBackBothWays() {
        assertEquals(work(), codec.decode(SessionMessage.TYPE, 1, codec.encode(work())));
        SessionMessage answer = new SessionMessage(new CommandId("s-a_7-0_3-0"), new RunRef(USER, B, new RunId("r")), "42 files", ID, false, 1,
                null, null, null);
        assertEquals(answer, codec.decode(SessionMessage.TYPE, 1, codec.encode(answer)));
        assertFalse(codec.encode(work()).contains("answers"), "work handed over has no \"answers\"");
    }

    @Test
    void theCommandThePhaseTheOwnerAndTheInboxReadBack() {
        RunState waiting = RunState.start(FROM, new OwnerRef.OfMessage(new CommandId("m-0"), new SessionId("main-1"), 1))
                .withPhase(new AwaitingReply(Instant.parse("2026-11-16T03:10:00Z"), new CommandId("a_7-1")));
        SessionChange change = new SessionChange(A, USER, false, List.of(), null, null, null, null, null, null, null, null, null,
                null, null, null, null, new SessionChange.Changed<>(Inbox.EMPTY.add(work())));
        StepRecord record = new StepRecord(7, change, List.of(waiting), List.of(), null, null, List.of(new Send(ID, FROM, B, work())), List.of());
        String line = records.encode(record, RecordMemo.writer(USER, A));
        assertTrue(line.contains("\"kind\":\"AwaitingReply\"") && line.contains("\"inbox\""), line);
        assertEquals(record, records.decode(line, RecordMemo.reader(USER, A)).record());
    }

    @Test
    void anOrdinarySessionWritesNothingNew() {
        SessionChange change = new SessionChange(A, USER, true, List.of(), null, null, null, null, null, null, null);
        String line = records.encode(new StepRecord(0, change, List.of(), List.of(), null, null, List.of(), List.of()),
                RecordMemo.writer(USER, A));
        assertFalse(line.contains("inbox"), line);
        assertFalse(line.contains("offRecord"), line);
    }
}
