package tantalum.kernel.step;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import tantalum.kernel.table.StepContext;
import tantalum.value.command.AbortCommands;
import tantalum.value.command.CancelDelegate;
import tantalum.value.command.Delegate;
import tantalum.value.command.Return;
import tantalum.value.command.StartTimer;
import tantalum.value.decision.Decision;
import tantalum.value.decision.HookReported;
import tantalum.value.delegate.DelegateCall;
import tantalum.value.event.DelegateCancelled;
import tantalum.value.event.DelegateReturned;
import tantalum.value.event.Delegated;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.TimerFired;
import tantalum.value.id.CommandId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.Message;
import tantalum.value.model.Role;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolResultBlock;
import tantalum.value.state.AwaitingDelegate;
import tantalum.value.state.EndReason;
import tantalum.value.state.Handoffs;
import tantalum.value.state.OwnerRef;
import tantalum.value.state.RunState;
import tantalum.value.state.SessionState;
import tantalum.value.state.ToolRound;
import tantalum.value.state.ToolSlot;

/**
 * 委派（kernel-delegation）。和别的步骤一样只在事件到来时走一步：
 * <ul>
 * <li>父运行的模型调了 {@code delegate}：发 {@link Delegate} 命令（交活），等结果的槽位标 {@code DELEGATED}，不等的马上完成。</li>
 * <li>没有在途命令、还有槽位在等交回：运行停在 {@link AwaitingDelegate}，没有线程在等。</li>
 * <li>{@link DelegateReturned}（交回）到了：有槽位在等就写成它的结果；没有就是一条汇报，叫醒会话或插话。</li>
 * <li>子会话收到 {@link Delegated}（交活）：空闲就开一轮（主人是这次交活），正忙就排队，重复的不重做。</li>
 * <li>主人是交活的一轮结束时发 {@link Return} 命令（交回），见 {@link #handBack}。</li>
 * </ul>
 * 一步只改事件所在的会话；跨会话只发命令，由服务器把事件送进对方会话的账。内核不读时钟、不读别的会话。
 */
public final class DelegateSteps {

    public static final String AWAITING_DELEGATE = AwaitingDelegate.NAME;
    /** 最多几层：普通会话是 0，它交出去的是 1。 */
    public static final int MAX_DEPTH = 2;
    /** 一轮工具调用里同时等着的委派最多几个。 */
    public static final int MAX_AT_ONCE = 4;
    /** 等结果最多等多久；长活用不等结果的委派。 */
    public static final Duration LIMIT = Duration.ofMinutes(10);
    /** 父会话放弃时，子运行结束的详情。 */
    public static final String STOPPED_BY_PARENT = "the conversation that delegated this stopped waiting";
    /** 忽略交回、交活时事实里的“钩子名”。 */
    static final String KERNEL = "kernel";

    private DelegateSteps() {}

    // ---- 父运行：交活 ----

    /**
     * 把第 {@code index} 个槽位（一次 {@code delegate} 调用）交出去，返回更新后的工具轮。参数不对、层数或个数超了时槽位直接得到错误结果，
     * 不交活。委派号就是这条 {@link Delegate} 命令的编号；新子会话的编号由它算出，回放时相同。
     */
    static ToolRound handOff(Outcome out, RunState run, ToolRound round, int index) {
        ToolSlot slot = round.slots().get(index);
        ToolCallBlock call = slot.call();
        DelegateCall.Parsed parsed;
        try {
            parsed = DelegateCall.parse(call.arguments());
        } catch (IllegalArgumentException invalid) {
            return failed(round, index, invalid.getMessage());
        }
        if (parsed.answer() != null) {
            return failed(round, index, "Answering a sub-conversation's question is not available yet; ask the user instead.");
        }
        SessionState session = out.session(run.ref());
        Handoffs place = session.handoffs();
        if (place.depth() + 1 > MAX_DEPTH) {
            return failed(round, index, "This conversation was itself delegated " + place.depth() + " levels deep; do the work here instead.");
        }
        long waiting = round.slots().stream().filter(each -> each.status() == ToolSlot.Status.DELEGATED).count();
        if (parsed.waits() && waiting >= MAX_AT_ONCE) {
            return failed(round, index, "At most " + MAX_AT_ONCE + " delegations can be waited for at once; wait for the others first.");
        }
        if (place.open().size() >= Handoffs.MAX_OPEN) {
            return failed(round, index, "Too many sub-conversations are still working (" + place.open().size()
                    + "); wait for some of them to report back.");
        }
        String prefix = "d-" + session.id().value() + "_";
        if (parsed.session() != null && !parsed.session().value().startsWith(prefix)) {
            return failed(round, index, "\"session\" must be a sub-conversation that this conversation started (its id starts with "
                    + prefix + ").");
        }
        CommandId delegation = out.nextCommandId();
        SessionId child = parsed.session() != null ? parsed.session() : DelegateCall.childOf(delegation);
        Delegated.Kind kind = parsed.session() != null ? Delegated.Kind.CONTINUE : Delegated.Kind.NEW;
        Delegated handoff = new Delegated(delegation, run.ref(), kind, DelegateCall.text(parsed, session.id(), kind == Delegated.Kind.NEW),
                null, place.depth() + 1, run.model(), session.dir(), session.offRecord() ? Boolean.TRUE : null, null);
        out.emit(new Delegate(delegation, run.ref(), child, handoff));
        out.put(session.withHandoffs(place.send(new Handoffs.Sent(child, handoff))));
        if (parsed.waits()) {
            return round.withSlot(index, slot.delegated(delegation));
        }
        return round.withSlot(index, slot.done(new ToolResultBlock(call.id(), "Started sub-conversation " + child.value()
                + "; its result will be reported back here when it is done.", false, null)));
    }

    private static ToolRound failed(ToolRound round, int index, String message) {
        ToolCallBlock call = round.slots().get(index).call();
        return round.withSlot(index, round.slots().get(index).done(new ToolResultBlock(call.id(), message, true, null)));
    }

    /** 只剩等交回的槽位：进 {@link AwaitingDelegate}，期限记成绝对时刻，定时器是命令。已经在等时不重起定时器。 */
    static void await(Outcome out, RunState run) {
        if (run.phase() instanceof AwaitingDelegate) {
            out.put(run);
            return;
        }
        CommandId timer = out.nextCommandId();
        out.emit(new StartTimer(timer, run.ref(), LIMIT));
        out.enter(run, new AwaitingDelegate(out.envelope.at().plus(LIMIT), timer));
    }

    // ---- 父运行：交回 ----

    /** 等工具或等委派时收到交回：委派号对得上一个在等的槽位就写成它的结果，对不上按汇报处理。 */
    public static Decision returned(StepContext context) {
        DelegateReturned event = (DelegateReturned) context.envelope().event();
        RunState run = context.run();
        ToolRound round = run.tools();
        int index = round == null ? -1 : slotOf(round, event.delegation());
        if (index < 0) {
            return reported(context);
        }
        Outcome out = new Outcome(context);
        ToolSlot slot = round.slots().get(index);
        SessionState session = out.session(run.ref());
        out.put(session.withHandoffs(session.handoffs().close(event.delegation())));
        RunState next = run.withTools(round.withSlot(index, slot.done(result(slot.call().id(), event))));
        settleTimer(out, next);
        ToolSteps.dispatch(out, next);
        return out.decision();
    }

    /** 没有槽位在等的交回是一条汇报：会话空闲就开一轮让模型处理，正在跑就作为插话。委派号不在“交出去的活”里的（重复的、放弃了的）忽略。 */
    public static Decision reported(StepContext context) {
        EventEnvelope envelope = context.envelope();
        DelegateReturned event = (DelegateReturned) envelope.event();
        SessionState session = context.state().sessions().get(envelope.session());
        RunState run = context.run();
        if (session == null || session.handoffs().sent(event.delegation()).isEmpty()) {
            return run == null ? Decision.unchanged(context.state())
                    : ignored(new Outcome(context), run.ref(), "return of " + event.delegation().value() + " ignored: nothing is waiting for it");
        }
        Outcome out = new Outcome(context);
        out.put(session.withHandoffs(session.handoffs().close(event.delegation())));
        ToolResultBlock result = result("report", event);
        Message message = Message.user("Report from a sub-conversation you delegated to earlier (not from the user).\n" + result.text());
        if (run == null || run.isEnded()) {
            SubmitSteps.startRun(out, new OwnerRef.OfSession(session.id()), message, null);
        } else {
            List<Message> steers = new ArrayList<>(run.pendingSteers());
            steers.add(message);
            out.put(run.withPendingSteers(steers));
        }
        return out.decision();
    }

    /** 期限到：还在等的槽位得到“超时”，让那些子会话停下。别的定时器（已经换过阶段的）忽略。 */
    public static Decision expired(StepContext context) {
        TimerFired event = (TimerFired) context.envelope().event();
        RunState run = context.run();
        if (!(run.phase() instanceof AwaitingDelegate waiting) || !event.command().equals(waiting.timer())) {
            return Decision.unchanged(context.state());
        }
        Outcome out = new Outcome(context);
        ToolRound round = run.tools();
        for (int i = 0; i < round.slots().size(); i++) {
            ToolSlot slot = round.slots().get(i);
            if (slot.status() == ToolSlot.Status.DELEGATED) {
                round = round.withSlot(i, slot.done(new ToolResultBlock(slot.call().id(), "The sub-conversation did not finish within "
                        + LIMIT.toMinutes() + " minutes and was told to stop. For long work, delegate with wait=false.", true, null)));
            }
        }
        abandon(out, run);
        ToolSteps.dispatch(out, run.withTools(round));
        return out.decision();
    }

    /**
     * 运行要结束了、或者不再等了：让还在等的那些子会话停下（{@link CancelDelegate}），并把它们从“交出去的活”里去掉，
     * 之后它们的交回不再算数。没有在等的槽位时什么都不做。
     */
    static void abandon(Outcome out, RunState run) {
        ToolRound round = run.tools();
        if (round == null) {
            return;
        }
        SessionState session = out.session(run.ref());
        Handoffs place = session.handoffs();
        List<CancelDelegate.Target> targets = new ArrayList<>();
        for (ToolSlot slot : round.slots()) {
            if (slot.status() != ToolSlot.Status.DELEGATED) {
                continue;
            }
            Optional<Handoffs.Sent> sent = place.sent(slot.command());
            if (sent.isPresent()) {
                targets.add(new CancelDelegate.Target(sent.get().child(), slot.command()));
                place = place.close(slot.command());
            }
        }
        if (!targets.isEmpty()) {
            out.emit(new CancelDelegate(out.nextCommandId(), run.ref(), targets));
            out.put(session.withHandoffs(place));
        }
    }

    /** 重启后：把交出去、还没交回的活再发一次。子会话按委派号去重：没收到的补上，做完的把交回再发一次。 */
    public static Decision recovered(StepContext context) {
        SessionState session = context.state().sessions().get(context.envelope().session());
        if (session == null || session.handoffs().open().isEmpty()) {
            return Decision.unchanged(context.state());
        }
        Outcome out = new Outcome(context);
        for (Handoffs.Sent sent : session.handoffs().open()) {
            out.emit(new Delegate(sent.handoff().delegation(), sent.handoff().from(), sent.child(), sent.handoff()));
        }
        return out.decision();
    }

    private static int slotOf(ToolRound round, CommandId delegation) {
        for (int i = 0; i < round.slots().size(); i++) {
            ToolSlot slot = round.slots().get(i);
            if (slot.status() == ToolSlot.Status.DELEGATED && delegation.equals(slot.command())) {
                return i;
            }
        }
        return -1;
    }

    /** 最后一个在等的槽位有了结果：期限的定时器不用了。 */
    private static void settleTimer(Outcome out, RunState run) {
        boolean stillWaiting = run.tools().slots().stream().anyMatch(slot -> slot.status() == ToolSlot.Status.DELEGATED);
        if (!stillWaiting && run.phase() instanceof AwaitingDelegate waiting && waiting.timer() != null) {
            out.emit(new AbortCommands(out.nextCommandId(), run.ref(), Set.of(waiting.timer())));
        }
    }

    /** 交回写成给模型的话：只有“结果”不算出错。 */
    static ToolResultBlock result(String callId, DelegateReturned event) {
        String child = event.child().value();
        String text = event.text().strip();
        return switch (event.kind()) {
            case RESULT -> text.isEmpty()
                    ? new ToolResultBlock(callId, "The sub-conversation " + child + " ended without an answer.", true, null)
                    : new ToolResultBlock(callId, "Result from sub-conversation " + child + ":\n" + text, false, null);
            case QUESTION -> new ToolResultBlock(callId, "The sub-conversation " + child + " asks before it can go on:\n" + text, false, null);
            case FAILED -> new ToolResultBlock(callId, "The sub-conversation " + child + " failed" + detail(text), true, null);
            case CANCELLED -> new ToolResultBlock(callId, "The sub-conversation " + child + " was cancelled by the user" + detail(text), true, null);
            case INTERRUPTED -> new ToolResultBlock(callId, "The sub-conversation " + child + " was interrupted by a server restart. Its "
                    + "conversation is kept: call delegate with session=\"" + child + "\" to let it go on.", true, null);
            case STOPPED -> new ToolResultBlock(callId, "The sub-conversation " + child + " was stopped" + detail(text), true, null);
            case REFUSED -> new ToolResultBlock(callId, "The sub-conversation " + child + " could not take the work" + detail(text), true, null);
        };
    }

    private static String detail(String text) {
        return text.isEmpty() ? "." : ": " + text;
    }

    // ---- 子会话：收到交活 ----

    /**
     * 交活到了这个会话。发的人必须是同一个用户；已经有父会话的只收它父会话的；有历史的普通会话不能被交活接管。
     * 同一个委派号再来（重启后重发）：正在做或在排队的不动，做完了的把交回再发一次。
     */
    public static Decision delegated(StepContext context) {
        EventEnvelope envelope = context.envelope();
        Delegated event = (Delegated) envelope.event();
        Outcome out = new Outcome(context);
        RunRef where = new RunRef(envelope.principal(), envelope.session(), tantalum.value.id.RunId.of(envelope.session(), envelope.seq()));
        if (!event.from().principal().equals(envelope.principal())) {
            return ignored(out, where, "hand-off " + event.delegation().value() + " ignored: it comes from another user");
        }
        SessionState existing = out.state().sessions().get(envelope.session());
        Handoffs place = existing == null ? Handoffs.NONE : existing.handoffs();
        SessionId parent = event.from().session();
        boolean stranger = place.isChild() ? !place.parent().equals(parent) : existing != null && !existing.messages().isEmpty();
        if (stranger) {
            out.emit(new Return(out.nextCommandId(), where, parent, new DelegateReturned(event.delegation(), envelope.session(), null,
                    DelegateReturned.Kind.REFUSED, "it is not a sub-conversation of " + parent.value(), null)));
            return out.decision();
        }
        RunState run = context.run();
        boolean busy = run != null && !run.isEnded();
        if (busy && run.owner() instanceof OwnerRef.OfCommand owner && owner.command().equals(event.delegation())
                || place.isQueued(event.delegation())) {
            return out.decision();
        }
        Optional<DelegateReturned> done = place.outcomeOf(event.delegation());
        if (done.isPresent()) {
            out.emit(new Return(out.nextCommandId(), where, parent, done.get()));
            return out.decision();
        }
        SessionState session = existing == null ? SessionState.start(envelope.session(), envelope.principal()) : existing;
        if (session.messages().isEmpty()) {
            // 工作目录、“不进记忆”和普通会话一样只在第一次定下；父会话、层数、限定的工具也是
            if (event.dir() != null) {
                session = session.withDir(event.dir());
            }
            if (Boolean.TRUE.equals(event.offRecord())) {
                session = session.withOffRecord();
            }
        }
        place = place.under(parent, event.depth(), event.tools());
        if (busy) {
            out.put(session.withHandoffs(place.enqueue(event)));
            return out.decision();
        }
        out.put(session.withHandoffs(place));
        start(out, event);
        return out.decision();
    }

    /** 父会话不等这次交活了：它开的运行正在跑就结束它，还在排队就去掉；别的不动。结束时照常交回，父会话那边已经不认了。 */
    public static Decision cancelled(StepContext context) {
        DelegateCancelled event = (DelegateCancelled) context.envelope().event();
        SessionState session = context.state().sessions().get(context.envelope().session());
        if (session == null) {
            return Decision.unchanged(context.state());
        }
        Outcome out = new Outcome(context);
        RunState run = context.run();
        if (run != null && !run.isEnded() && run.owner() instanceof OwnerRef.OfCommand owner && owner.command().equals(event.delegation())) {
            return CancelSteps.end(context, EndReason.CANCELLED, STOPPED_BY_PARENT);
        }
        if (session.handoffs().isQueued(event.delegation())) {
            List<Delegated> kept = new ArrayList<>(session.handoffs().queued());
            kept.removeIf(handoff -> handoff.delegation().equals(event.delegation()));
            Handoffs place = session.handoffs();
            out.put(session.withHandoffs(new Handoffs(place.parent(), place.depth(), kept, place.returned(), place.tools(), place.open())));
        }
        return out.decision();
    }

    /** 为一次交活开一轮：追加任务（用户角色的消息，但来自父运行），主人是这次交活。 */
    static void start(Outcome out, Delegated handoff) {
        SubmitSteps.startRun(out, new OwnerRef.OfCommand(handoff.delegation()), Message.user(handoff.text()), handoff.model());
    }

    // ---- 子会话：交回 ----

    /**
     * 主人是交活的一轮结束了：把结局交回给父会话，并记在会话里（重发的交活据此再交回一次）。{@code session} 是这一轮结束后的会话。
     * 返回记下结局后的会话，调用方写回。主人是会话自己的、或者会话没有父会话时原样返回。
     */
    static SessionState handBack(Outcome out, RunState run, SessionState session, EndReason reason, String detail) {
        if (!(run.owner() instanceof OwnerRef.OfCommand owner) || !session.handoffs().isChild()) {
            return session;
        }
        DelegateReturned.Kind kind = switch (reason) {
            case COMPLETED -> DelegateReturned.Kind.RESULT;
            case FAILED -> DelegateReturned.Kind.FAILED;
            case STOPPED -> DelegateReturned.Kind.STOPPED;
            case CANCELLED -> CancelSteps.INTERRUPTED_BY_RESTART.equals(detail) ? DelegateReturned.Kind.INTERRUPTED : DelegateReturned.Kind.CANCELLED;
        };
        String text = kind == DelegateReturned.Kind.RESULT ? lastAnswer(session)
                : kind == DelegateReturned.Kind.INTERRUPTED || detail == null ? "" : detail;
        DelegateReturned outcome = new DelegateReturned(owner.command(), session.id(), run.ref().run(), kind, text, null);
        out.emit(new Return(out.nextCommandId(), run.ref(), session.handoffs().parent(), outcome));
        return session.withHandoffs(session.handoffs().settle(outcome));
    }

    /** 这一轮最后的回答：会话最后一条消息是助手的才算，取它的文字。 */
    private static String lastAnswer(SessionState session) {
        if (session.messages().isEmpty() || session.messages().getLast().role() != Role.ASSISTANT) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : session.messages().getLast().content()) {
            if (block instanceof TextBlock part) {
                text.append(part.text());
            }
        }
        return text.toString();
    }

    private static Decision ignored(Outcome out, RunRef where, String why) {
        out.report(List.of(new HookReported(where, KERNEL, why)));
        return out.decision();
    }
}
