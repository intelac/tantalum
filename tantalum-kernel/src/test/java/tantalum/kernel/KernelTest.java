package tantalum.kernel;

import tantalum.value.state.RunCause;
import tantalum.value.state.SessionStateFixture;
import tantalum.value.id.SubmissionId;

import tantalum.value.event.UserPrompt;

import tantalum.value.decision.StepFailed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import tantalum.value.state.RunState;
import tantalum.value.state.SessionState;
import tantalum.value.event.UserPromptFixture;

class KernelTest {

    private static final Principal USER = new Principal("u");
    private static final SessionId SESSION = new SessionId("s");
    private static final RunRef A = new RunRef(USER, SESSION, new RunId("a"));
    private static final RunRef B = new RunRef(USER, new SessionId("s2"), new RunId("b"));
    private static final CommandId WAITING = new CommandId("1-0");

    private static RunState awaiting(RunRef ref) {
        return RunState.start(ref, RunCause.prompt())
                .withPhase(new Awaiting(Set.of(WAITING), "AwaitingRetry"));
    }

    private static KernelState state() {
        return KernelState.empty().with(awaiting(A)).with(awaiting(B))
                .with(SessionStateFixture.regular(SESSION, USER).startRun(A.run()));
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

    /** 一轮已经结束了还抛异常：不再收一次尾（那会再交一次结果、清掉会话上别的运行），只留一条事实。 */
    @Test
    void aStepFailureOnAnEndedRunDoesNotEndItAgain() {
        TransitionTable table = new TransitionTable();
        table.register(TransitionTable.ANY_STATE, "TimerFired", context -> { throw new IllegalStateException("boom"); });
        RunState ended = RunState.start(A, RunCause.prompt()).withPhase(new Ended(EndReason.COMPLETED, null));
        RunRef live = new RunRef(USER, SESSION, new RunId("live"));
        KernelState before = KernelState.empty().with(ended).with(awaiting(live))
                .with(SessionStateFixture.regular(SESSION, USER).startRun(live.run()));
        Decision decision = kernel(table).step(before, timer(A, "late"));
        assertSame(before, decision.newState());
        assertEquals(List.of(new StepFailed(new RunRef(USER, SESSION, RunId.of(SESSION, 5)), "TimerFired", "IllegalStateException")),
                decision.facts());
    }

    /** 隔离：会话是别人的，任何事件都什么都不改、不发、不报（入口之外的第二道）。 */
    @Test
    void anEventForSomeoneElsesSessionChangesNothing() {
        TransitionTable table = new TransitionTable();
        table.register("AwaitingRetry", "TimerFired", context -> { throw new AssertionError("must not run"); });
        KernelState before = state();
        EventEnvelope foreign = new EventEnvelope(5, Instant.EPOCH, new Principal("mallory"), SESSION, A.run(), new TimerFired(WAITING));
        Decision decision = kernel(table).step(before, foreign);
        assertSame(before, decision.newState());
        assertTrue(decision.commands().isEmpty() && decision.facts().isEmpty());
    }

    /** 信封里点名的运行是别的会话的：当作没有运行，不碰它。 */
    @Test
    void aRunOfAnotherSessionCannotBeNamed() {
        TransitionTable table = new TransitionTable();
        table.register("AwaitingRetry", "TimerFired", context -> { throw new AssertionError("must not run"); });
        KernelState before = state();
        EventEnvelope borrowed = new EventEnvelope(5, Instant.EPOCH, USER, SESSION, B.run(), new TimerFired(WAITING));
        assertSame(before, kernel(table).step(before, borrowed).newState());
    }

    /** review-fixes 需求 5.1：没有运行可以结束时，步骤函数的异常变成一条 StepFailed 事实，状态不变。 */
    @Test
    void aStepFailureWithoutARunIsReportedAsAFact() {
        TransitionTable table = new TransitionTable();
        table.register(Kernel.NEW_RUN, "UserPrompt", context -> { throw new IllegalStateException("boom"); });
        KernelState before = KernelState.empty();
        EventEnvelope prompt = new EventEnvelope(5, Instant.EPOCH, USER, SESSION, null,
                UserPromptFixture.of(new SubmissionId("sub"), new RequestId("r"), "hi"));
        Decision decision = kernel(table).step(before, prompt);
        assertSame(before, decision.newState());
        assertEquals(List.of(new StepFailed(new RunRef(USER, SESSION, RunId.of(SESSION, 5)), "UserPrompt", "IllegalStateException")),
                decision.facts());
    }
}
