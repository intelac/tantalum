package tantalum.shell.handler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.shell.skill.Agents;
import tantalum.value.command.Acknowledge;
import tantalum.value.command.Command;
import tantalum.value.command.Deliver;
import tantalum.value.delivery.AssignmentParcel;
import tantalum.value.delivery.ReportParcel;
import tantalum.value.delivery.StopParcel;
import tantalum.value.event.Assignment;
import tantalum.value.event.Delivered;
import tantalum.value.event.Event;
import tantalum.value.event.Input;
import tantalum.value.event.Refused;
import tantalum.value.event.SessionCreated;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;
import tantalum.value.state.HelperOf;
import tantalum.value.state.SessionKind;

/**
 * 会话之间的送达（delivery）：{@code Deliver} 把送方信箱里的一条放进对方会话，{@code Acknowledge} 把“送到了 / 不收”放回送方。
 * 都是往**另一个会话**放事件，经入口（{@code Intake}）。不留状态、不重试、不等：一时放不进去（环满、索引还没写到）什么都不做，
 * 送方的确认定时器到了会重送；只有确定永远不收的（未知 agent、帮手会话的编号已经是别人的、这种会话不收这种东西）才回一条
 * {@link Refused}，作为这条命令自己的结果回到送方。
 */
public final class DeliveryHandler implements Handler {

    /** 入口里会话之间送达的两个方法。返回 HTTP 状态：200 放进了环，404 会话不是这个人的或者还没有，409 不收，429 环满。 */
    public interface Deliveries {
        int deliver(Principal principal, SessionId session, SessionCreated create, Input input);

        int handOver(Principal principal, SessionId session, Event event);

        /** 这个人的这个会话已经有了。 */
        boolean exists(Principal principal, SessionId session);
    }

    private static final Logger LOG = LoggerFactory.getLogger(DeliveryHandler.class);

    private final Deliveries deliveries;
    private final Agents agents;

    public DeliveryHandler(Deliveries deliveries, Agents agents) {
        this.deliveries = deliveries;
        this.agents = agents;
    }

    @Override
    public Event handle(Command command) throws InterruptedException {
        Principal user = command.run().principal();
        return switch (command) {
            case Deliver deliver -> deliver(user, deliver);
            case Acknowledge ack -> {
                // 回执本身不再确认：放不进去就算了，送方会重送、收方认出重复再回
                deliveries.handOver(user, ack.to(), ack.refused() == null ? new Delivered(ack.delivery()) : new Refused(ack.delivery(), ack.refused()));
                yield null;
            }
            default -> throw new IllegalArgumentException("not a delivery command: " + command.getClass().getSimpleName());
        };
    }

    private Event deliver(Principal user, Deliver deliver) throws InterruptedException {
        return switch (deliver.what()) {
            case AssignmentParcel given -> assign(user, deliver, given);
            case ReportParcel back -> deliveries.deliver(user, deliver.to(), null, back.input()) == 409
                    ? new Refused(back.id(), "that session does not take results") : null;
            case StopParcel stop -> {
                // 帮手会话没有：交代没送到过（送方已经撤掉了它），没有可停的
                if (!deliveries.exists(user, deliver.to())) {
                    yield new Refused(stop.id(), "there is no session for that task");
                }
                deliveries.handOver(user, deliver.to(), stop.input());
                yield null;
            }
        };
    }

    /** 交代：读 agent 的定义，帮手会话还没有就照它建（帮手的出身在这里定下），放进那条交代。 */
    private Event assign(Principal user, Deliver deliver, AssignmentParcel given) throws InterruptedException {
        SessionId issuer = deliver.run().session();
        Assignment input = given.input();
        // 重送（“送到了”丢了）：帮手已经建好，出身早定下了，不再读 agent（本机一时连不上不能变成“不收”），直接送，帮手认出重复只回确认
        if (deliveries.exists(user, deliver.to())) {
            deliveries.deliver(user, deliver.to(), null, input);
            return null;
        }
        Agents.Found found = agents.find(user, deliver.to(), given.agent(), given.dir());
        if (found.agent() == null && given.agent() != null) {
            return new Refused(given.id(), found.problem());
        }
        Agents.Agent agent = found.agent();
        SessionCreated helper = new SessionCreated(SessionKind.HELPER, given.dir(), given.offRecord(), new HelperOf(issuer, input.id(),
                given.agent(), agent == null ? null : agent.instructions(), agent == null ? null : agent.tools()));
        Assignment assignment = agent == null || agent.model() == null ? input
                : new Assignment(input.id(), input.issuer(), input.root(), input.prompt(), agent.model());
        // 帮手会话归交代它的那个人：入口先认领编号。编号已经是别人的（多用户）就不开始，不往别人的会话里送东西
        int status = deliveries.deliver(user, deliver.to(), helper, assignment);
        if (status == 404 || status == 409) {
            return new Refused(given.id(), "Not started: the server could not open a session for this task. Do the work yourself or start it again.");
        }
        return null;
    }

    /** 送达没有自己的失败事件：没送到的由送方的确认定时器重送。 */
    @Override
    public Event failed(Command command, Exception failure) {
        LOG.error("delivery command {} failed", command.id().value(), failure);
        return null;
    }
}
