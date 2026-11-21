package tantalum.shell.loop;

import tantalum.value.state.SessionStateFixture;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tantalum.kernel.step.CancelSteps;
import tantalum.value.event.Report;
import tantalum.value.event.Root;
import tantalum.value.state.RunCause;
import tantalum.value.id.CommandId;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;
import tantalum.value.model.Message;
import tantalum.value.model.Role;
import tantalum.value.model.TextBlock;
import tantalum.value.state.Awaiting;
import tantalum.value.state.EndReason;
import tantalum.value.state.Ended;
import tantalum.value.state.RunPhase;
import tantalum.value.state.RunState;
import tantalum.value.state.SessionState;
import tantalum.value.assignment.AssignmentOutcome;

/**
 * 启动时核对放出去还没回来的任务（subagent 需求 4.2、4.3）：上一个进程死在哪个时间点，放出任务的会话都恰好得到一个结果，
 * 不重新放出；还在跑的只重新起期限。
 */
class TaskRecoveryTest {

    private static final Principal USER = new Principal("alice");
    private static final SessionId PARENT = new SessionId("s1");
    private static final Instant NOW = Instant.parse("2026-11-17T12:00:00Z");
    private static final Instant DEADLINE = Instant.parse("2026-11-17T12:20:00Z");

    private static TaskRecovery.Outstanding task(String id) {
        return new TaskRecovery.Outstanding(PARENT, USER, id, new SessionId("s1-" + id), DEADLINE, new CommandId("timer-" + id));
    }

    private static TaskRecovery.Child child(String id, RunPhase phase, String answer) {
        SessionId session = new SessionId("s1-" + id);
        SessionState state = SessionStateFixture.helper(session, USER, PARENT, id).append(Message.assignment(id, "Check module A."));
        if (answer != null) {
            state = state.append(new Message(Role.ASSISTANT, List.of(new TextBlock(answer)), false));
        }
        RunState run = RunState.start(new RunRef(USER, session, RunId.of(session, 1)), new RunCause(RunCause.StartedBy.ASSIGNMENT, Root.USER, id)).withPhase(phase);
        return new TaskRecovery.Child(state, List.of(run));
    }

    private static TaskRecovery.Plan plan(Map<String, TaskRecovery.Child> children, String... ids) {
        return TaskRecovery.plan(List.of(ids).stream().map(TaskRecoveryTest::task).toList(),
                (user, session) -> children.get(session.value().substring("s1-".length())), NOW);
    }

    private static Report only(TaskRecovery.Plan plan) {
        assertEquals(1, plan.settle().size(), plan.toString());
        assertTrue(plan.rearm().isEmpty(), plan.toString());
        assertEquals(PARENT, plan.settle().getFirst().session());
        assertEquals(USER, plan.settle().getFirst().principal());
        assertNull(plan.settle().getFirst().run());
        return (Report) plan.settle().getFirst().event();
    }

    @Test
    void aTaskThatNeverArrivedIsInterruptedAndNotStartedAgain() {
        assertEquals(new Report("t1-0", AssignmentOutcome.INTERRUPTED, null, Root.USER), only(plan(Map.of(), "t1-0")));
        // 会话在，但为任务开的那一轮没有：也是没送到
        assertEquals(new Report("t1-0", AssignmentOutcome.INTERRUPTED, null, Root.USER),
                only(plan(Map.of("t1-0", new TaskRecovery.Child(SessionStateFixture.regular(new SessionId("s1-t1-0"), USER), List.of())), "t1-0")));
    }

    @Test
    void aTaskThatEndedButWasNotHandedBackGetsTheResultItHad() {
        assertEquals(new Report("t1-0", AssignmentOutcome.COMPLETED, "Module A is fine.", Root.USER),
                only(plan(Map.of("t1-0", child("t1-0", new Ended(EndReason.COMPLETED, null), "Module A is fine.")), "t1-0")));
        assertEquals(new Report("t1-0", AssignmentOutcome.FAILED, "model refused", Root.USER),
                only(plan(Map.of("t1-0", child("t1-0", new Ended(EndReason.FAILED, "model refused"), null)), "t1-0")));
        assertEquals(new Report("t1-0", AssignmentOutcome.STOPPED, null, Root.USER),
                only(plan(Map.of("t1-0", child("t1-0", new Ended(EndReason.CANCELLED, null), "half done")), "t1-0")));
        // 这次重启刚把它收尾：和它自己交回的是同一个结果，内核按名单去重
        assertEquals(new Report("t1-0", AssignmentOutcome.INTERRUPTED, CancelSteps.INTERRUPTED_BY_RESTART, Root.USER),
                only(plan(Map.of("t1-0", child("t1-0", new Ended(EndReason.CANCELLED, CancelSteps.INTERRUPTED_BY_RESTART), null)), "t1-0")));
    }

    @Test
    void aTaskStillRunningOnlyGetsItsDeadlineBack() {
        TaskRecovery.Plan plan = plan(Map.of("t1-0", child("t1-0", new Awaiting(Set.of(), "AwaitingAnswer"), null)), "t1-0", "t1-1");

        // t1-1 没送到，t1-0 还在等用户回答
        assertEquals(1, plan.settle().size());
        assertEquals("t1-1", ((Report) plan.settle().getFirst().event()).assignment());
        assertEquals(List.of(new AnswerDeadline(PARENT, USER, null, DEADLINE, new CommandId("timer-t1-0"))), plan.rearm());
    }
}
