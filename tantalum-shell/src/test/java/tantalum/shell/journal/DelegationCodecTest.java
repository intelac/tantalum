package tantalum.shell.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.value.command.CancelDelegate;
import tantalum.value.command.Delegate;
import tantalum.value.command.Return;
import tantalum.value.decision.SessionChange;
import tantalum.value.decision.StepRecord;
import tantalum.value.event.DelegateReturned;
import tantalum.value.event.Delegated;
import tantalum.value.event.Recovered;
import tantalum.value.id.CommandId;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;
import tantalum.value.state.AwaitingDelegate;
import tantalum.value.state.Handoffs;
import tantalum.value.state.OwnerRef;
import tantalum.value.state.RunState;

/**
 * kernel-delegation D1：交活、交回、重启后恢复三种事件，三种命令，等委派的状态，会话在委派里的位置，写进账都能原样读回；
 * 和委派无关的会话一个字也不多写。
 */
class DelegationCodecTest {

    private static final Principal USER = new Principal("alice");
    private static final SessionId PARENT = new SessionId("s-1");
    private static final SessionId CHILD = new SessionId("d-s-1_7-0");
    private static final CommandId DELEGATION = new CommandId("s-1_7-0");
    private static final RunRef FROM = new RunRef(USER, PARENT, new RunId("s-1_5"));

    private final EventCodec codec = EventCodec.withBuiltins();
    private final RecordCodec records = new RecordCodec(codec);

    private static Delegated handoff() {
        return new Delegated(DELEGATION, FROM, Delegated.Kind.NEW, "Task:\ncount the files", null, 1, null, "/home/alice/app", null,
                List.of("read_file", "grep"));
    }

    private static DelegateReturned outcome() {
        return new DelegateReturned(DELEGATION, CHILD, new RunId("d-s-1_7-0_1"), DelegateReturned.Kind.RESULT, "42 files", null);
    }

    @Test
    void theThreeEventsReadBack() {
        assertEquals(handoff(), codec.decode(Delegated.TYPE, 1, codec.encode(handoff())));
        assertEquals(outcome(), codec.decode(DelegateReturned.TYPE, 1, codec.encode(outcome())));
        DelegateReturned question = new DelegateReturned(DELEGATION, CHILD, null, DelegateReturned.Kind.QUESTION, "Which folder?", "ask-c1");
        assertEquals(question, codec.decode(DelegateReturned.TYPE, 1, codec.encode(question)));
        assertEquals(new Recovered(), codec.decode(Recovered.TYPE, 1, codec.encode(new Recovered())));
        tantalum.value.event.DelegateCancelled stopped = new tantalum.value.event.DelegateCancelled(DELEGATION);
        assertEquals(stopped, codec.decode(tantalum.value.event.DelegateCancelled.TYPE, 1, codec.encode(stopped)));
    }

    @Test
    void commandsThePhaseAndTheSessionsPlaceReadBack() {
        RunRef childRun = new RunRef(USER, CHILD, new RunId("d-s-1_7-0_1"));
        RunState waiting = RunState.start(FROM, new OwnerRef.OfSession(PARENT))
                .withPhase(new AwaitingDelegate(Instant.parse("2026-11-16T03:10:00Z"), new CommandId("s-1_7-1")));
        Handoffs place = Handoffs.NONE.under(PARENT, 1, List.of("grep")).enqueue(handoff()).settle(outcome())
                .send(new Handoffs.Sent(CHILD, handoff()));
        SessionChange change = new SessionChange(PARENT, USER, false, List.of(), null, null, null, null, null, null, null, null, null,
                null, null, null, null, new SessionChange.Changed<>(place));
        StepRecord record = new StepRecord(7, change, List.of(waiting), List.of(), null, null,
                List.of(new Delegate(DELEGATION, FROM, CHILD, handoff()), new Return(new CommandId("d-1"), childRun, PARENT, outcome()),
                        new CancelDelegate(new CommandId("s-1_9-0"), FROM, List.of(new CancelDelegate.Target(CHILD, DELEGATION)))), List.of());
        String line = records.encode(record, RecordMemo.writer(USER, PARENT));
        assertTrue(line.contains("\"kind\":\"AwaitingDelegate\""), line);
        assertEquals(record, records.decode(line, RecordMemo.reader(USER, PARENT)).record());
    }

    @Test
    void anOrdinarySessionWritesNothingAboutDelegation() {
        SessionChange change = new SessionChange(PARENT, USER, true, List.of(), null, null, null, null, null, null, null);
        String line = records.encode(new StepRecord(0, change, List.of(), List.of(), null, null, List.of(), List.of()),
                RecordMemo.writer(USER, PARENT));
        assertFalse(line.contains("handoffs"), line);
        assertFalse(line.contains("offRecord"), line);
    }
}
