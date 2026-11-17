package tantalum.shell.handler;

import java.time.Clock;
import java.time.Duration;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.shell.skill.Agents;
import tantalum.value.command.Command;
import tantalum.value.command.ReturnTask;
import tantalum.value.command.StartTask;
import tantalum.value.command.StopTask;
import tantalum.value.event.Event;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.TaskAssigned;
import tantalum.value.event.TaskCancelled;
import tantalum.value.event.TaskEnded;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;
import tantalum.value.task.TaskOutcome;

/**
 * 任务的三条命令：都是往**另一个会话**的账里放一条事件，所以不走“结果发回命令所在的会话”那条路，自己经输入环放，
 * 对这条命令本身不产生结果（内核也不等它）。不留状态：放不进去（环满）就隔一会儿再试，一直放不进去就放弃，
 * 那个任务可以由人停掉，或者由下次启动时的核对给出结果。
 *
 * <p>{@code StartTask}：读 agent 的定义，往为任务开的会话放 {@code TaskAssigned}；点了不认识的 agent，任务不开始，
 * 直接往放出它的会话放一条失败的 {@code TaskEnded}，写明有哪些。{@code ReturnTask}：往放出它的会话放 {@code TaskEnded}。
 * {@code StopTask}：给任务的会话放 {@code TaskCancelled}；它没有在跑的运行时直接给放出它的会话一个“已停止”。
 */
public final class TaskHandler implements Handler {

    private static final Logger LOG = LoggerFactory.getLogger(TaskHandler.class);
    private static final Duration PUBLISH_RETRY = Duration.ofMillis(10);
    private static final int PUBLISH_ATTEMPTS = 500;

    private final Clock clock;
    private final Predicate<EventEnvelope> publish;
    private final Agents agents;
    private final BiPredicate<Principal, SessionId> running;

    /** {@code publish} 把事件放进输入环，放不进去返回 false；{@code running} 回答一个会话现在有没有一轮在跑。 */
    public TaskHandler(Clock clock, Predicate<EventEnvelope> publish, Agents agents, BiPredicate<Principal, SessionId> running) {
        this.clock = clock;
        this.publish = publish;
        this.agents = agents;
        this.running = running;
    }

    @Override
    public Event handle(Command command) throws InterruptedException {
        Principal user = command.run().principal();
        switch (command) {
            case StartTask start -> assign(user, start);
            case ReturnTask back -> send(user, back.parent(), new TaskEnded(back.task(), back.outcome(), back.text()));
            case StopTask stop -> stop(user, stop);
            default -> throw new IllegalArgumentException("not a task command: " + command.getClass().getSimpleName());
        }
        return null;
    }

    private void assign(Principal user, StartTask start) throws InterruptedException {
        Agents.Found found = agents.find(user, start.session(), start.agent(), start.dir());
        if (found.agent() == null && start.agent() != null) {
            send(user, start.run().session(), new TaskEnded(start.task(), TaskOutcome.FAILED, found.problem()));
            return;
        }
        Agents.Agent agent = found.agent();
        send(user, start.session(), new TaskAssigned(start.task(), start.run().session(), start.prompt(), start.agent(),
                agent == null ? null : agent.instructions(), agent == null ? null : agent.tools(),
                agent == null || agent.model() == null ? start.model() : agent.model(),
                start.dir(), start.offRecord()));
    }

    /**
     * 任务那边有一轮在跑：让它停，它自己交回“已停止”。没有在跑的（没送到、已经结束而结果没送回）：直接给放出它的会话
     * 一个“已停止”，这样停止一定能把那条记录清掉；真的结果要是随后到了，名单里已经没有它，不生效。
     */
    private void stop(Principal user, StopTask stop) throws InterruptedException {
        if (running.test(user, stop.session())) {
            send(user, stop.session(), new TaskCancelled(stop.task()));
        } else {
            send(user, stop.run().session(), new TaskEnded(stop.task(), TaskOutcome.STOPPED, null));
        }
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

    /** 这条命令没有自己的失败事件：没送到的任务由停止或启动时的核对收尾。 */
    @Override
    public Event failed(Command command, Exception failure) {
        LOG.error("task command {} failed", command.id().value(), failure);
        return null;
    }
}
