package tantalum.value.decision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.RunInterrupted;
import tantalum.value.id.CommandId;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;
import tantalum.value.model.Message;
import tantalum.value.model.TokenUsage;
import tantalum.value.state.Awaiting;
import tantalum.value.state.Compaction;
import tantalum.value.state.EndReason;
import tantalum.value.state.Ended;
import tantalum.value.state.KernelState;
import tantalum.value.state.OwnerRef;
import tantalum.value.state.PrincipalState;
import tantalum.value.state.RunState;
import tantalum.value.state.SessionState;
import tantalum.value.state.UsageAnchor;

class StepRecordTest {

    private static final Principal USER = new Principal("u");
    private static final SessionId SESSION = new SessionId("s");
    private static final SessionId OTHER = new SessionId("other");
    private static final RunRef REF = new RunRef(USER, SESSION, new RunId("s_0"));
    private static final Instant AT = Instant.parse("2026-11-04T01:00:00Z");
    private static final LocalDate DAY = LocalDate.parse("2026-11-04");
    private static final EventEnvelope EVENT = new EventEnvelope(7, AT, USER, SESSION, null, new RunInterrupted());

    /** 套回去必须得到和实时相同的状态：这是恢复不再调内核的前提。 */
    private static StepRecord roundTrip(KernelState before, KernelState after) {
        Decision decision = new Decision(after, List.of(), List.of());
        StepRecord record = StepRecord.of(EVENT, before, decision);
        assertEquals(after, record.apply(EVENT, before).newState());
        return record;
    }

    @Test
    void aNewSessionWithItsFirstRun() {
        SessionState session = SessionState.start(SESSION, USER).append(Message.user("hi")).startRun(REF.run());
        RunState run = RunState.start(REF, new OwnerRef.OfSession(SESSION));
        KernelState after = KernelState.empty().with(session).with(run);

        StepRecord record = roundTrip(KernelState.empty(), after);

        assertTrue(record.session().created());
        assertEquals(List.of(Message.user("hi")), record.session().appended());
        assertEquals(List.of(run), record.runs());
    }

    @Test
    void onlyAppendedMessagesAndChangedFieldsAreRecorded() {
        SessionState session = SessionState.start(SESSION, USER).append(Message.user("a")).append(Message.user("b"))
                .startRun(REF.run());
        KernelState before = KernelState.empty().with(session);
        SessionState later = session.append(Message.user("c")).withAnchor(new UsageAnchor(900, 3));

        StepRecord record = roundTrip(before, before.with(later));

        assertEquals(List.of(Message.user("c")), record.session().appended());
        assertEquals(new UsageAnchor(900, 3), record.session().anchor().value());
        assertNull(record.session().activeRun());
        assertNull(record.session().followUps());
    }

    @Test
    void fieldsThatBecomeNullAreRecorded() {
        SessionState running = SessionState.start(SESSION, USER).startRun(REF.run()).withAnchor(new UsageAnchor(1, 1));
        KernelState before = KernelState.empty().with(running);

        StepRecord record = roundTrip(before, before.with(running.endRun().compacted(
                new Compaction(List.of(Message.user("summary")), 0))));

        assertEquals(new SessionChange.Changed<RunId>(null), record.session().activeRun());
        assertEquals(new SessionChange.Changed<UsageAnchor>(null), record.session().anchor());
    }

    @Test
    void queuedMessagesAndEndedRuns() {
        SessionState session = SessionState.start(SESSION, USER).startRun(REF.run());
        RunState run = RunState.start(REF, new OwnerRef.OfSession(SESSION))
                .withPhase(new Awaiting(Set.of(new CommandId("s_0-0")), "AwaitingModel"));
        KernelState before = KernelState.empty().with(session).with(run);
        KernelState after = before.with(session.withFollowUps(List.of(Message.user("next"))))
                .with(run.withPhase(new Ended(EndReason.CANCELLED, "interrupted by restart")));

        StepRecord record = roundTrip(before, after);

        assertEquals(List.of(Message.user("next")), record.session().followUps().value());
        assertEquals(1, record.runs().size());
    }

    @Test
    void usageIsTheDifferenceOfTheStep() {
        PrincipalState spent = PrincipalState.start(USER).add(DAY, new TokenUsage(100, 10, 0, 0));
        KernelState before = KernelState.empty().with(spent);

        StepRecord record = roundTrip(before, before.with(spent.add(DAY, new TokenUsage(5, 1, 0, 0))));
        assertEquals(new TokenUsage(5, 1, 0, 0), record.usage());
        assertEquals(DAY, record.usageDay());

        // 单个文件折叠时，处理前的用量只有这个会话自己的部分，差值照样套得上
        assertEquals(new TokenUsage(5, 1, 0, 0),
                record.apply(EVENT, KernelState.empty()).newState().principals().get(USER).usageOn(DAY));
    }

    @Test
    void aZeroUsageDayStillMatches() {
        roundTrip(KernelState.empty(), KernelState.empty().with(PrincipalState.start(USER).add(DAY, TokenUsage.ZERO)));
    }

    @Test
    void anUnchangedStepRecordsNothing() {
        KernelState state = KernelState.empty().with(SessionState.start(SESSION, USER));

        StepRecord record = roundTrip(state, state);

        assertNull(record.session());
        assertTrue(record.runs().isEmpty());
        assertNull(record.usage());
    }

    @Test
    void rewritingHistoryIsRefused() {
        SessionState session = SessionState.start(SESSION, USER).append(Message.user("a"));
        KernelState before = KernelState.empty().with(session);
        SessionState rewritten = SessionState.start(SESSION, USER).append(Message.user("b"));

        assertThrows(IllegalStateException.class,
                () -> StepRecord.of(EVENT, before, new Decision(before.with(rewritten), List.of(), List.of())));
    }

    @Test
    void touchingAnotherSessionIsRefused() {
        KernelState before = KernelState.empty();
        KernelState after = before.with(SessionState.start(OTHER, USER));

        assertThrows(IllegalStateException.class,
                () -> StepRecord.of(EVENT, before, new Decision(after, List.of(), List.of())));
    }

    @Test
    void touchingAnotherUsersUsageIsRefused() {
        KernelState after = KernelState.empty().with(PrincipalState.start(new Principal("v")).add(DAY, TokenUsage.ZERO));

        assertThrows(IllegalStateException.class,
                () -> StepRecord.of(EVENT, KernelState.empty(), new Decision(after, List.of(), List.of())));
    }

    @Test
    void usageAndItsDayGoTogether() {
        assertThrows(IllegalArgumentException.class,
                () -> new StepRecord(1, null, List.of(), List.of(), DAY, null, List.of(), List.of()));
        assertEquals(Map.of(), new StepRecord(1, null, List.of(), List.of(), null, null, List.of(), List.of())
                .apply(EVENT, KernelState.empty()).newState().principals());
    }
}
