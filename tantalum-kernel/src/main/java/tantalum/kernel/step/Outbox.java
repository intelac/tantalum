package tantalum.kernel.step;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import tantalum.value.command.AbortCommands;
import tantalum.value.command.Acknowledge;
import tantalum.value.command.Deliver;
import tantalum.value.command.StartTimer;
import tantalum.value.decision.Decision;
import tantalum.value.delivery.AssignmentParcel;
import tantalum.value.delivery.Outgoing;
import tantalum.value.delivery.Parcel;
import tantalum.value.event.CommandDropped;
import tantalum.value.event.Delivered;
import tantalum.value.event.Refused;
import tantalum.value.id.CommandId;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;
import tantalum.value.state.SessionState;
import tantalum.kernel.table.StepContext;

/**
 * 送方信箱（delivery 需求 1）：送给别的会话的东西先记在这里，对方确认才划掉。同一个对方前一条确认了才发下一条；
 * 到时没确认照原编号重送；启动时由 {@code Redeliver} 再发。一步只改送方自己的信箱，送进对方由处理器做。
 */
public final class Outbox {

    /** 发出后等这么久没确认就重送。 */
    public static final Duration ACK_AFTER = Duration.ofSeconds(10);

    private Outbox() {}

    /** 送达、确认命令挂在哪个运行上：会话最近的一轮；还没开过一轮的（新的帮手）按这一步的序号起一个，和内核留事实时一样。 */
    static RunRef ref(Outcome out, SessionState session) {
        RunId run = session.lastRun() != null ? session.lastRun() : RunId.of(session.id(), out.envelope.seq());
        return new RunRef(session.principal(), session.id(), run);
    }

    /** 记进 {@code from} 的信箱；同一个对方前面没有待确认的就马上发。同一编号已经在信箱里时不再记。 */
    static void send(Outcome out, SessionId from, SessionId to, Parcel what) {
        SessionState session = out.state().sessions().get(from);
        if (session.outbox().stream().anyMatch(each -> each.id().equals(what.id()))) {
            return; // 同一条已经在等确认（比如叫停了两次）：不再记一条
        }
        List<Outgoing> box = new ArrayList<>(session.outbox());
        boolean queued = box.stream().anyMatch(each -> each.to().equals(to));
        Outgoing entry = new Outgoing(to, what, null);
        box.add(queued ? entry : dispatch(out, session, entry));
        out.put(session.withOutbox(box));
    }

    /** 发出一条：送达命令加确认定时器。 */
    private static Outgoing dispatch(Outcome out, SessionState session, Outgoing entry) {
        RunRef ref = ref(out, session);
        CommandId timer = out.nextCommandId();
        out.emit(new Deliver(out.nextCommandId(), ref, entry.to(), entry.what()));
        out.emit(new StartTimer(timer, ref, ACK_AFTER));
        return entry.sent(timer);
    }

    /** 收的一方回“送到了”（{@code refused} 为 null）或“不收”。 */
    static void acknowledge(Outcome out, SessionState session, String delivery, SessionId to, String refused) {
        out.emit(new Acknowledge(out.nextCommandId(), ref(out, session), delivery, to, refused));
    }

    /** 对方确认收到：划掉，停掉定时器，发同一个对方的下一条。信箱里没有（重复的确认）什么都不改。 */
    public static Decision delivered(StepContext context) {
        Delivered event = (Delivered) context.envelope().event();
        SessionState session = context.state().sessions().get(context.envelope().session());
        if (session == null || find(session, event.id()).isEmpty()) {
            return Decision.unchanged(context.state());
        }
        Outcome out = new Outcome(context);
        Outgoing done = find(session, event.id()).get();
        remove(out, session, done, Set.of(done.id()));
        return out.decision();
    }

    /**
     * 对方确定不收：划掉；是交代的，按失败的结果结算（名额放掉、期限停掉），排在后面的同一个交代的叫停一起划掉。
     * 信箱里没有（已经确认过、重复的不收）什么都不改。
     */
    public static Decision refused(StepContext context) {
        Refused event = (Refused) context.envelope().event();
        SessionState session = context.state().sessions().get(context.envelope().session());
        if (session == null || find(session, event.id()).isEmpty()) {
            return Decision.unchanged(context.state());
        }
        Outcome out = new Outcome(context);
        Outgoing done = find(session, event.id()).get();
        Set<String> gone = new java.util.HashSet<>(Set.of(done.id()));
        if (done.what() instanceof AssignmentParcel given) {
            session.outbox().stream().filter(each -> each.what().assignment().equals(given.assignment())).forEach(each -> gone.add(each.id()));
        }
        remove(out, session, done, gone);
        if (done.what() instanceof AssignmentParcel given) {
            AssignmentSteps.refused(out, given.assignment(), event.reason());
        }
        return out.decision();
    }

    /** 启动时：每个对方的第一条照原编号再发（原来的定时器随进程没了），起新的定时器。 */
    public static Decision redeliver(StepContext context) {
        SessionState session = context.state().sessions().get(context.envelope().session());
        if (session == null || session.outbox().isEmpty()) {
            return Decision.unchanged(context.state());
        }
        Outcome out = new Outcome(context);
        List<Outgoing> box = new ArrayList<>();
        Set<SessionId> heads = new java.util.HashSet<>();
        for (Outgoing each : session.outbox()) {
            box.add(heads.add(each.to()) ? dispatch(out, session, each) : each);
        }
        out.put(out.state().sessions().get(session.id()).withOutbox(box));
        return out.decision();
    }

    /** 确认定时器到了（或者它因为环满被丢了）：照原编号重送。不是确认定时器返回 false。 */
    static boolean resend(Outcome out, SessionState session, CommandId timer) {
        List<Outgoing> box = new ArrayList<>(session.outbox());
        for (int i = 0; i < box.size(); i++) {
            if (timer.equals(box.get(i).ackTimer())) {
                box.set(i, dispatch(out, session, box.get(i)));
                out.put(out.state().sessions().get(session.id()).withOutbox(box));
                return true;
            }
        }
        return false;
    }

    /** {@code CommandDropped}：丢的是确认定时器就重送，否则交给原来的处理。 */
    public static Decision commandDropped(StepContext context) {
        CommandDropped event = (CommandDropped) context.envelope().event();
        SessionState session = context.state().sessions().get(context.envelope().session());
        if (session != null && session.outbox().stream().anyMatch(each -> event.command().equals(each.ackTimer()))) {
            Outcome out = new Outcome(context);
            resend(out, session, event.command());
            return out.decision();
        }
        return CancelSteps.commandDropped(context);
    }

    private static Optional<Outgoing> find(SessionState session, String id) {
        return session.outbox().stream().filter(each -> each.id().equals(id)).findFirst();
    }

    /** 划掉 {@code gone} 里的几条，停掉已发出那条的定时器，然后同一个对方剩下的第一条没发的就发。 */
    private static void remove(Outcome out, SessionState session, Outgoing done, Set<String> gone) {
        if (done.ackTimer() != null) {
            out.emit(new AbortCommands(out.nextCommandId(), ref(out, session), Set.of(done.ackTimer())));
        }
        List<Outgoing> box = new ArrayList<>(session.outbox().stream().filter(each -> !gone.contains(each.id())).toList());
        for (int i = 0; i < box.size(); i++) {
            if (box.get(i).to().equals(done.to())) {
                if (box.get(i).ackTimer() == null) {
                    box.set(i, dispatch(out, session, box.get(i)));
                }
                break;
            }
        }
        out.put(out.state().sessions().get(session.id()).withOutbox(box));
    }
}
