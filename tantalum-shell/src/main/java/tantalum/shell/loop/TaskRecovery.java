package tantalum.shell.loop;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import tantalum.kernel.step.TaskSteps;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.TaskEnded;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;
import tantalum.value.state.Ended;
import tantalum.value.state.OwnerRef;
import tantalum.value.state.RunState;
import tantalum.value.state.SessionState;
import tantalum.value.task.TaskOutcome;

/**
 * 启动时核对放出去还没回来的任务（subagent 需求 4.2、4.3）。上一个进程可能死在“放出了、还没送到”或“结束了、还没送回”之间，
 * 这时放出任务的会话名单里还有它，却不会再有人给结果。逐个看任务自己的会话：
 *
 * <ul>
 * <li>不存在：任务没送到。结果是“中断”，不重新放出（和重启不重新执行工具同一个理由）。</li>
 * <li>它那一轮已经结束：按它账里的结局补发（和正常结束时交回的是同一个结果）。</li>
 * <li>它那一轮还在（重启不打断等用户回答的运行）：不动，只把期限的定时器重新起来。</li>
 * </ul>
 *
 * 补发的结果可能和正常送到的重复：内核按名单去重，第二个不生效。这里是纯计算，不做 I/O。
 */
public final class TaskRecovery {

    /** 一个任务自己的会话现在的样子；会话不存在为 null。 */
    public record Child(SessionState session, List<RunState> runs) {}

    /** {@code settle} 是要补发给放出任务的会话的事件；{@code rearm} 是还在跑、要重新起期限的。 */
    public record Plan(List<EventEnvelope> settle, List<AnswerDeadline> rearm) {}

    /** 一个还没回来的任务：{@code session} 是放出它的会话。 */
    public record Outstanding(SessionId session, Principal principal, String task, SessionId child, Instant deadline,
                              tantalum.value.id.CommandId timer) {}

    private TaskRecovery() {}

    public static Plan plan(List<Outstanding> outstanding, BiFunction<Principal, SessionId, Child> children, Instant now) {
        List<EventEnvelope> settle = new ArrayList<>();
        List<AnswerDeadline> rearm = new ArrayList<>();
        for (Outstanding task : outstanding) {
            Child child = children.apply(task.principal(), task.child());
            Optional<TaskEnded> ended = ended(task, child);
            if (ended.isPresent()) {
                settle.add(new EventEnvelope(EventEnvelope.UNASSIGNED, now, task.principal(), task.session(), null, ended.get()));
            } else {
                // 期限的定时器不属于哪一轮：信封不带运行，内核按“没人等的定时器”认出它是哪个任务的
                rearm.add(new AnswerDeadline(task.session(), task.principal(), null, task.deadline(), task.timer()));
            }
        }
        return new Plan(settle, rearm);
    }

    private static Optional<TaskEnded> ended(Outstanding task, Child child) {
        if (child == null || child.session() == null) {
            return Optional.of(new TaskEnded(task.task(), TaskOutcome.INTERRUPTED, null));
        }
        for (RunState run : child.runs()) {
            if (!(run.owner() instanceof OwnerRef.OfTask)) {
                continue;
            }
            if (run.phase() instanceof Ended end) {
                TaskOutcome outcome = TaskSteps.outcomeOf(end.reason(), end.detail());
                return Optional.of(new TaskEnded(task.task(), outcome, TaskSteps.textOf(outcome, child.session(), end.detail())));
            }
            return Optional.empty();
        }
        // 会话在，但没有为任务开的那一轮：TaskAssigned 没有生效过
        return Optional.of(new TaskEnded(task.task(), TaskOutcome.INTERRUPTED, null));
    }
}
