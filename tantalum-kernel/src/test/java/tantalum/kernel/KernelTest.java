package tantalum.kernel;

import tantalum.value.id.SubmissionId;

import tantalum.value.event.UserPrompt;

import tantalum.value.decision.StepFailed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tantalum.kernel.hook.HookChain;
import tantalum.kernel.table.TransitionTable;
import tantalum.value.decision.Decision;
import tantalum.value.event.Cancel;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.TimerFired;
import tantalum.value.id.CommandId;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;
import tantalum.value.state.Awaiting;
import tantalum.value.state.EndReason;
import tantalum.value.state.Ended;
import tantalum.value.state.KernelState;
import tantalum.value.state.OwnerRef;
import tantalum.value.state.RunState;
import tantalum.value.state.SessionState;

class KernelTest {

    private static final Principal USER = new Principal("u");
    private static final SessionId SESSION = new SessionId("s");
    private static final RunRef A = new RunRef(USER, SESSION, new RunId("a"));
    private static final RunRef B = new RunRef(USER, new SessionId("s2"), new RunId("b"));
    private static final CommandId WAITING = new CommandId("1-0");

    private static RunState awaiting(RunRef ref) {
        return RunState.start(ref, new OwnerRef.OfSession(ref.session()))
                .withPhase(new Awaiting(Set.of(WAITING), "AwaitingRetry"));
    }

    private static KernelState state() {
        return KernelState.empty().with(awaiting(A)).with(awaiting(B))
                .with(SessionState.start(SESSION, USER).startRun(A.run()));
    }

    private static EventEnvelope timer(RunRef ref, String command) {
        return new EventEnvelope(5, Instant.EPOCH, ref.principal(), ref.session(), ref.run(),
                new TimerFired(new CommandId(command)));
    }

    private static Kernel kernel(TransitionTable table) {
        return new Kernel(table, new HookChain(List.of()), (tool, args) -> false, Limits.DEFAULT);
    }

    @Test
    void sameInputGivesSameOutput() {
        TransitionTable table = new TransitionTable();
        table.register("AwaitingRetry", "TimerFired",
                context -> Decision.unchanged(context.state().with(context.run().withRetries(1))));
        Kernel kernel = kernel(table);
        assertEquals(kernel.step(state(), timer(A, "1-0")), kernel.step(state(), timer(A, "1-0")));
    }

    @Test
    void stepFindsTheRowByStateNameAndEventType() {
        TransitionTable table = new TransitionTable();
        table.register("AwaitingRetry", "TimerFired",
                context -> Decision.unchanged(context.state().with(context.run().withRetries(1))));
        Decision decision = kernel(table).step(state(), timer(A, "1-0"));
        assertEquals(1, decision.newState().runs().get(A.run()).retries());
    }

    @Test
    void eventWithoutARowChangesNothing() {
        KernelState before = state();
        assertSame(before, kernel(new TransitionTable()).step(before, timer(A, "1-0")).newState());
    }

    @Test
    void staleCommandIdChangesNothing() {
        TransitionTable table = new TransitionTable();
        table.register("AwaitingRetry", "TimerFired",
                context -> { throw new AssertionError("must not run"); });
        KernelState before = state();
        assertSame(before, kernel(table).step(before, timer(A, "9-9")).newState());
    }

    @Test
    void resultForAnEndedRunChangesNothing() {
        TransitionTable table = new TransitionTable();
        table.register("Ended", "TimerFired", context -> { throw new AssertionError("must not run"); });
        KernelState before = state().with(awaiting(A).withPhase(new Ended(EndReason.CANCELLED, null)));
        assertSame(before, kernel(table).step(before, timer(A, "1-0")).newState());
    }

    @Test
    void anyStateRowStillReceivesLateResults() {
        TransitionTable table = new TransitionTable();
        table.register(TransitionTable.ANY_STATE, "TimerFired",
                context -> Decision.unchanged(context.state().with(context.run().withRetries(7))));
        Decision decision = kernel(table).step(state(), timer(A, "9-9"));
        assertEquals(7, decision.newState().runs().get(A.run()).retries());
    }

    @Test
    void stepExceptionOnlyFailsThatRun() {
        TransitionTable table = new TransitionTable();
        table.register("AwaitingRetry", "TimerFired", context -> { throw new IllegalStateException("boom"); });
        Decision decision = kernel(table).step(state(), timer(A, "1-0"));

        Ended ended = assertInstanceOf(Ended.class, decision.newState().runs().get(A.run()).phase());
        assertEquals(EndReason.FAILED, ended.reason());
        assertInstanceOf(Awaiting.class, decision.newState().runs().get(B.run()).phase());
        assertEquals(null, decision.newState().sessions().get(SESSION).activeRun());
        assertEquals(1, decision.facts().size());
    }

    @Test
    void exceptionWithoutARunChangesNothing() {
        TransitionTable table = new TransitionTable();
        table.register(Kernel.NEW_RUN, "Cancel", context -> { throw new IllegalStateException("boom"); });
        KernelState before = KernelState.empty();
        EventEnvelope cancel = new EventEnvelope(1, Instant.EPOCH, USER, SESSION, null, new Cancel(new RequestId("r")));
        assertSame(before, kernel(table).step(before, cancel).newState());
    }

    /** review-fixes 需求 5.1：没有运行可以结束时，步骤函数的异常变成一条 StepFailed 事实，状态不变。 */
    @Test
    void aStepFailureWithoutARunIsReportedAsAFact() {
        TransitionTable table = new TransitionTable();
        table.register(Kernel.NEW_RUN, "UserPrompt", context -> { throw new IllegalStateException("boom"); });
        KernelState before = KernelState.empty();
        EventEnvelope prompt = new EventEnvelope(5, Instant.EPOCH, USER, SESSION, null,
                new UserPrompt(new SubmissionId("sub"), new RequestId("r"), new OwnerRef.OfSession(SESSION), "hi"));
        Decision decision = kernel(table).step(before, prompt);
        assertSame(before, decision.newState());
        assertEquals(List.of(new StepFailed(new RunRef(USER, SESSION, RunId.of(SESSION, 5)), "UserPrompt", "IllegalStateException")),
                decision.facts());
    }
}
