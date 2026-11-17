package tantalum.shell.handler;

import java.time.Clock;
import java.time.Duration;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.value.command.Command;
import tantalum.value.command.ReturnTask;
import tantalum.value.command.StartTask;
import tantalum.value.command.StopTask;
import tantalum.value.event.Cancel;
import tantalum.value.event.Event;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.TaskAssigned;
import tantalum.value.event.TaskEnded;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.SessionId;
import tantalum.value.task.TaskOutcome;

/**
 * 任务的三条命令：都是往**另一个会话**的账里放一条事件，所以不走“结果发回命令所在的会话”那条路，自己经输入环放，
 * 对这条命令本身不产生结果（内核也不等它）。不留状态：放不进去（环满）就隔一会儿再试，一直放不进去就放弃，
 * 那个任务由期限的定时器或下次启动时的核对给出结果。
 *
 * <p>{@code StartTask}：读 agent 的定义，往为任务开的会话放 {@code TaskAssigned}；点了不认识的 agent，任务不开始，
 * 直接往放出它的会话放一条失败的 {@code TaskEnded}，写明有哪些。{@code ReturnTask}：往放出它的会话放 {@code TaskEnded}。
 * {@code StopTask}：给任务的会话发 {@code Cancel}，和用户按停止是同一个事件。
 */
public final class TaskHandler implements Handler {

    private static final Logger LOG = LoggerFactory.getLogger(TaskHandler.class);
    private static final Duration PUBLISH_RETRY = Duration.ofMillis(10);
    private static final int PUBLISH_ATTEMPTS = 500;

    private final Clock clock;
    private final Predicate<EventEnvelope> publish;
    private final Agents agents;

    /** {@code publish} 把事件放进输入环，放不进去返回 false。 */
    public TaskHandler(Clock clock, Predicate<EventEnvelope> publish, Agents agents) {
        this.clock = clock;
        this.publish = publish;
        this.agents = agents;
    }

    @Override
    public Event handle(Command command) throws InterruptedException {
        Principal user = command.run().principal();
        switch (command) {
            case StartTask start -> assign(user, start);
            case ReturnTask back -> send(user, back.parent(), new TaskEnded(back.task(), back.outcome(), back.text()));
            case StopTask stop -> send(user, stop.session(), new Cancel(new RequestId("task-stop-" + stop.id().value())));
            default -> throw new IllegalArgumentException("not a task command: " + command.getClass().getSimpleName());
        }
        return null;
    }

    private void assign(Principal user, StartTask start) throws InterruptedException {
        Agents.Found found = agents.find(user, start.agent(), start.dir());
        if (found.agent() == null && start.agent() != null) {
            send(user, start.run().session(), new TaskEnded(start.task(), TaskOutcome.FAILED, found.problem()));
            return;
        }
        Agents.Agent agent = found.agent();
        send(user, start.session(), new TaskAssigned(start.task(), start.run().session(), start.prompt(), start.agent(),
                agent == null ? null : agent.instructions(), agent == null ? null : agent.tools(), agent == null ? null : agent.model(),
                start.dir(), start.offRecord()));
    }

    private void send(Principal user, SessionId session, Event event) throws InterruptedException {
        EventEnvelope envelope = new EventEnvelope(EventEnvelope.UNASSIGNED, clock.instant(), user, session, null, event);
        for (int attempt = 0; attempt < PUBLISH_ATTEMPTS; attempt++) {
            if (publish.test(envelope)) {
                return;
            }
            Thread.sleep(PUBLISH_RETRY);
        }
        LOG.error("could not hand {} to session {}: the input ring stayed full", event.type(), session.value());
    }

    /** 这条命令没有自己的失败事件：没送到的任务由期限或启动时的核对收尾。 */
    @Override
    public Event failed(Command command, Exception failure) {
        LOG.error("task command {} failed", command.id().value(), failure);
        return null;
    }
}
