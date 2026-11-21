package tantalum.shell.handler;

import java.time.Duration;
import java.util.function.BiPredicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.shell.skill.Agents;
import tantalum.value.assignment.AssignmentOutcome;
import tantalum.value.command.Assign;
import tantalum.value.command.Command;
import tantalum.value.command.ReturnReport;
import tantalum.value.command.StopAssignment;
import tantalum.value.event.Assignment;
import tantalum.value.event.Event;
import tantalum.value.event.Input;
import tantalum.value.event.Report;
import tantalum.value.event.Root;
import tantalum.value.event.SessionCreated;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;
import tantalum.value.state.HelperOf;
import tantalum.value.state.SessionKind;

/**
 * 交代的三条命令：都是往**另一个会话**放一条输入，所以不走“结果发回命令所在的会话”那条路，经入口（{@code Intake}）送，
 * 对这条命令本身不产生结果（内核也不等它）。不留状态：送不进去（环满）就隔一会儿再试，一直送不进去就放弃，
 * 那次交代可以由人叫停，或者由下次启动时的核对给出结果。
 *
 * <p>{@code Assign}：读 agent 的定义，建帮手会话（{@code SessionCreated}，帮手的出身在这里定下），放进那条 {@code Assignment}；
 * 点了不认识的 agent，不开始，直接给交代的会话一条失败的 {@code Report}，写明有哪些。{@code ReturnReport}：给交代的会话一条 {@code Report}。
 * {@code StopAssignment}：帮手有一轮在跑就让它停（{@code AssignmentCancelled}）；没有就直接给交代的会话一个“已停止”。
 */
public final class AssignmentHandler implements Handler {

    /** 入口里会话之间送达的那两个方法。返回 HTTP 状态，429 表示环满了、等一会儿再送。 */
    public interface Deliveries {
        int deliver(Principal principal, SessionId session, SessionCreated create, Input input);

        int cancel(Principal principal, SessionId session, String assignment);
    }

    private static final Logger LOG = LoggerFactory.getLogger(AssignmentHandler.class);
    private static final Duration RETRY = Duration.ofMillis(10);
    private static final int ATTEMPTS = 500;

    private final Deliveries deliveries;
    private final Agents agents;
    private final BiPredicate<Principal, SessionId> running;

    /** {@code running} 回答一个会话现在有没有一轮在跑。 */
    public AssignmentHandler(Deliveries deliveries, Agents agents, BiPredicate<Principal, SessionId> running) {
        this.deliveries = deliveries;
        this.agents = agents;
        this.running = running;
    }

    @Override
    public Event handle(Command command) throws InterruptedException {
        Principal user = command.run().principal();
        switch (command) {
            case Assign start -> assign(user, start);
            case ReturnReport back -> report(user, back.issuer(), new Report(back.assignment(), back.outcome(), back.text(), back.root()));
            case StopAssignment stop -> stop(user, stop);
            default -> throw new IllegalArgumentException("not an assignment command: " + command.getClass().getSimpleName());
        }
        return null;
    }

    private void assign(Principal user, Assign start) throws InterruptedException {
        SessionId issuer = start.run().session();
        Agents.Found found = agents.find(user, start.session(), start.agent(), start.dir());
        if (found.agent() == null && start.agent() != null) {
            report(user, issuer, new Report(start.assignment(), AssignmentOutcome.FAILED, found.problem(), start.root()));
            return;
        }
        Agents.Agent agent = found.agent();
        SessionCreated helper = new SessionCreated(SessionKind.HELPER, start.dir(), start.offRecord(), new HelperOf(issuer, start.assignment(),
                start.agent(), agent == null ? null : agent.instructions(), agent == null ? null : agent.tools()));
        Assignment assignment = new Assignment(start.assignment(), issuer, start.root(), start.prompt(),
                agent == null || agent.model() == null ? start.model() : agent.model());
        // 帮手会话归交代它的那个人：入口先认领编号。编号已经是别人的（多用户）就不开始，说明原因，不往别人的会话里送东西
        int status = send(() -> deliveries.deliver(user, start.session(), helper, assignment), start.session());
        if (status != 200 && status != 429) {
            report(user, issuer, new Report(start.assignment(), AssignmentOutcome.FAILED,
                    "Not started: the server could not open a session for this task. Do the work yourself or start it again.", start.root()));
        }
    }

    /**
     * 帮手那边有一轮在跑：让它停，它自己交回“已停止”。没有在跑的（没送到、已经结束而结果没送回）：直接给交代的会话
     * 一个“已停止”，这样叫停一定能把那条记录清掉；真的结果要是随后到了，名单里已经没有它，不生效。
     */
    private void stop(Principal user, StopAssignment stop) throws InterruptedException {
        if (running.test(user, stop.session())) {
            send(() -> deliveries.cancel(user, stop.session(), stop.assignment()), stop.session());
        } else {
            // 内核取交代时的和这里的两者里低的，所以写“你”不会把它抬高
            report(user, stop.run().session(), new Report(stop.assignment(), AssignmentOutcome.STOPPED, null, Root.USER));
        }
    }

    private void report(Principal user, SessionId issuer, Report report) throws InterruptedException {
        send(() -> deliveries.deliver(user, issuer, null, report), issuer);
    }

    private interface Attempt {
        int once();
    }

    /** 环满了就等一会儿再试；返回最后一次的状态。 */
    private static int send(Attempt attempt, SessionId session) throws InterruptedException {
        int status = 429;
        for (int i = 0; i < ATTEMPTS && status == 429; i++) {
            status = attempt.once();
            if (status == 429) {
                Thread.sleep(RETRY);
            }
        }
        if (status != 200) {
            LOG.error("could not hand an assignment message to session {}: status {}", session.value(), status);
        }
        return status;
    }

    /** 这条命令没有自己的失败事件：没送到的交代由叫停或启动时的核对收尾。 */
    @Override
    public Event failed(Command command, Exception failure) {
        LOG.error("assignment command {} failed", command.id().value(), failure);
        return null;
    }
}
