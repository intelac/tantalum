package tantalum.shell.handler;

import java.util.List;
import tantalum.value.delivery.NoticeParcel;
import tantalum.value.id.SessionIds;
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

    /** 这个人登记的工作区（主会话派活时按名字找目录，dispatch）。 */
    public interface Workspaces {
        List<tantalum.shell.project.Project> of(Principal user) throws Exception;
    }

    private final Deliveries deliveries;
    private final Agents agents;
    private final Workspaces workspaces;

    public DeliveryHandler(Deliveries deliveries, Agents agents, Workspaces workspaces) {
        this.deliveries = deliveries;
        this.agents = agents;
        this.workspaces = workspaces;
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
            // 记一笔：主会话不收（不该发生）才回不收；一时进不去等重送
            case NoticeParcel note -> deliveries.deliver(user, deliver.to(), null, note.input()) == 409
                    ? new Refused(note.id(), "that conversation does not take notes") : null;
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
            // 收的一方不收（种类不对、不是主会话的、不进记忆的）由内核回；这里只处理入口直接拒的
            return deliveries.deliver(user, deliver.to(), null, input) == 409
                    ? new Refused(given.id(), "that conversation does not take tasks") : null;
        }
        if (!SessionIds.helperShaped(deliver.to())) {
            return dispatched(user, deliver, given, issuer);
        }
        Agents.Found found = agents.find(user, deliver.to(), given.agent(), given.dir());
        if (found.agent() == null && given.agent() != null) {
            return new Refused(given.id(), found.problem());
        }
        Agents.Agent agent = found.agent();
        SessionCreated helper = new SessionCreated(SessionKind.HELPER, given.dir(), given.offRecord(), new HelperOf(issuer, input.id(),
                given.agent(), agent == null ? null : agent.instructions(), agent == null ? null : agent.tools()), null);
        Assignment assignment = agent == null || agent.model() == null ? input
                : new Assignment(input.id(), input.issuer(), input.root(), input.prompt(), agent.model());
        // 帮手会话归交代它的那个人：入口先认领编号。编号已经是别人的（多用户）就不开始，不往别人的会话里送东西
        int status = deliveries.deliver(user, deliver.to(), helper, assignment);
        if (status == 404 || status == 409) {
            return new Refused(given.id(), "Not started: the server could not open a session for this task. Do the work yourself or start it again.");
        }
        return null;
    }

    /**
     * 主会话派活（dispatch 需求 2）：点名的会话不在（或是别人的）就不收；派活编号的会话还没有，就照点名的工作区查目录建一个普通会话，
     * 记着是哪个主会话开的。工作区名字不对：不收，写明有哪些。
     */
    private Event dispatched(Principal user, Deliver deliver, AssignmentParcel given, SessionId issuer) throws InterruptedException {
        if (!SessionIds.workShaped(deliver.to())) {
            return new Refused(given.id(), "there is no conversation " + deliver.to().value() + " of yours. Leave \"session\" out to open a new one.");
        }
        String dir = null;
        if (given.workspace() != null) {
            List<tantalum.shell.project.Project> mine;
            try {
                mine = workspaces.of(user);
            } catch (InterruptedException stopping) {
                throw stopping;
            } catch (Exception unreadable) {
                // 一时读不了工作区：不回，送方到时重送
                LOG.warn("cannot read the workspaces of {}: {}", user.value(), unreadable.toString());
                return null;
            }
            dir = mine.stream().filter(project -> project.title().equalsIgnoreCase(given.workspace()) || project.id().equals(given.workspace()))
                    .map(tantalum.shell.project.Project::path).findFirst().orElse(null);
            if (dir == null) {
                return new Refused(given.id(), "there is no workspace named \"" + given.workspace() + "\". The workspaces are: "
                        + (mine.isEmpty() ? "(none)" : String.join(", ", mine.stream().map(tantalum.shell.project.Project::title).toList()))
                        + ". Leave \"workspace\" out for a conversation without a folder.");
            }
        }
        SessionCreated work = new SessionCreated(SessionKind.REGULAR, dir, false, null, issuer);
        int status = deliveries.deliver(user, deliver.to(), work, given.input());
        if (status == 404 || status == 409) {
            return new Refused(given.id(), "Not started: the server could not open a conversation for this task.");
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
