package tantalum.shell.loop;

import tantalum.kernel.step.AssignmentSteps;
import tantalum.value.assignment.AssignmentOutcome;
import tantalum.value.event.Report;
import tantalum.value.event.Root;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import tantalum.value.event.EventEnvelope;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;
import tantalum.value.state.Ended;
import tantalum.value.state.RunState;
import tantalum.value.state.SessionState;

/**
 * 启动时核对交代出去还没回来的（给模型和人的说法是“任务”）（subagent 需求 4.2、4.3）。上一个进程可能死在“放出了、还没送到”或“结束了、还没送回”之间，
 * 这时放出任务的会话名单里还有它，却不会再有人给结果。逐个看任务自己的会话：
 *
 * <ul>
 * <li>不存在：任务没送到。结果是“中断”，不重新放出（和重启不重新执行工具同一个理由）。</li>
 * <li>它那一轮已经结束：按它账里的结局补发（和正常结束时交回的是同一个结果）。</li>
 * <li>它那一轮还在（重启不打断等用户回答的运行）：不动；设了期限的把期限的定时器重新起来。</li>
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
            Optional<Report> ended = ended(task, child);
            if (ended.isPresent()) {
                settle.add(new EventEnvelope(EventEnvelope.UNASSIGNED, now, task.principal(), task.session(), null, ended.get()));
            } else if (task.deadline() != null) {
                // 期限的定时器不属于哪一轮：信封不带运行，内核按“没人等的定时器”认出它是哪个任务的
                rearm.add(new AnswerDeadline(task.session(), task.principal(), null, task.deadline(), task.timer()));
            }
        }
        return new Plan(settle, rearm);
    }

    private static Optional<Report> ended(Outstanding task, Child child) {
        if (child == null || child.session() == null) {
            return Optional.of(interrupted(task));
        }
        for (RunState run : child.runs()) {
            if (!task.task().equals(run.cause().owes())) {
                continue;
            }
            if (run.phase() instanceof Ended end) {
                AssignmentOutcome outcome = AssignmentSteps.outcomeOf(end.reason(), end.detail());
                return Optional.of(new Report(task.task(), outcome, AssignmentSteps.textOf(outcome, child.session(), task.task(), end.detail()),
                        run.cause().root()));
            }
            return Optional.empty();
        }
        // 会话在，但没有为这次交代开的那一轮：交代没有生效过
        return Optional.of(interrupted(task));
    }

    /** 内核取交代时的和这里的两者里低的，所以写“你”不会把它抬高。 */
    private static Report interrupted(Outstanding task) {
        return new Report(task.task(), AssignmentOutcome.INTERRUPTED, null, Root.USER);
    }
}
