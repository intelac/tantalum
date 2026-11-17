package tantalum.kernel.step;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import tantalum.kernel.table.StepContext;
import tantalum.value.command.AbortCommands;
import tantalum.value.command.Send;
import tantalum.value.command.StartTimer;
import tantalum.value.decision.Decision;
import tantalum.value.decision.HookReported;
import tantalum.value.delegate.DelegateCall;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.SessionMessage;
import tantalum.value.event.TimerFired;
import tantalum.value.id.CommandId;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.Message;
import tantalum.value.model.Role;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolResultBlock;
import tantalum.value.state.AwaitingReply;
import tantalum.value.state.EndReason;
import tantalum.value.state.OwnerRef;
import tantalum.value.state.RunState;
import tantalum.value.state.SessionState;
import tantalum.value.state.ToolRound;
import tantalum.value.state.ToolSlot;

/**
 * 会话之间说话。只有一种行为：有人对一个会话说一句话，会话跑一轮，把结果交回给说话的人。说话的是另一个会话时：
 * <ul>
 * <li>模型调 {@code delegate}：内核发 {@link Send}（送话）。不等的当场完成；要等的槽位标 {@code ASKED}，没有别的命令在跑时
 * 运行停在 {@link AwaitingReply}，没有线程在等。</li>
 * <li>{@link SessionMessage}（会话来话）到了：是交来的活，空闲就开一轮（主人是这句话），正忙就排队；是回话，对得上在等的槽位
 * 就写成工具结果，否则当一条消息（空闲开一轮，正在跑作为插话）。</li>
 * <li>为回一句话而开的一轮结束时，把结果作为一条回话送回去，见 {@link #answer}。</li>
 * <li>为回一句话而开的一轮停下来等用户回答时，先告诉说话的会话一声“在等人”，见 {@link #tellWaiting}；回话以后照常送。</li>
 * </ul>
 * 没有“子 agent”这一类东西，没有父子层级；防止一层套一层只靠话上的“转了几手”。一步只改事件所在的会话，内核不读时钟、不读别的会话。
 */
public final class MessageSteps {

    public static final String AWAITING_REPLY = AwaitingReply.NAME;
    /**
     * 一句话最多转几手。1：用户发起的一轮可以交给另一个会话，为回它而开的那一轮不能再往下交，自己做。所以只有一层。
     * （2026-11-16 端到端：允许两手时，接活的本机模型搜到主窗口里“调用 delegate”的原话，就照着又转交了一次，没有干活。）
     */
    public static final int MAX_HOPS = 1;
    /** 一轮工具调用里同时等着回话的最多几个。 */
    public static final int MAX_ASKED = 4;
    /** 一轮工具调用里最多送出几句话。 */
    public static final int MAX_SENT = 8;
    /** 等回话最多等多久；长活用不等的。 */
    public static final Duration LIMIT = Duration.ofMinutes(10);
    /**
     * 用户不在场时，一个会话自己最多再往外交几件活。回话到了、会话空闲，会自己开一轮处理；那一轮又可以交活，对方回话又开一轮……
     * 两个会话能这样一直转下去，没有人看着。所以从用户最后一次说话算起，这样的轮次总共只能再交这么多件；到了就得停下来向用户
     * 汇报，用户一说话就重新算。用户自己那一轮交多少件不受这个限制。
     */
    public static final int MAX_UNATTENDED = 8;
    /** 忽略来话时事实里的“钩子名”。 */
    static final String KERNEL = "kernel";

    private MessageSteps() {}

    // ---- 说话的一方 ----

    /**
     * 处理第 {@code index} 个槽位（一次 {@code delegate} 调用），返回更新后的工具轮。参数不对、转手太多、这一轮送得太多时槽位得到错误，
     * 不送。话的编号就是这条 {@link Send} 命令的编号；新会话的编号由它算出，回放时相同。
     */
    static ToolRound say(Outcome out, RunState run, ToolRound round, int index) {
        ToolSlot slot = round.slots().get(index);
        ToolCallBlock call = slot.call();
        DelegateCall.Parsed parsed;
        try {
            parsed = DelegateCall.parse(call.arguments());
        } catch (IllegalArgumentException invalid) {
            return done(round, index, invalid.getMessage(), true);
        }
        if (parsed.stop()) {
            return takeBack(out, run, round, index, parsed.session());
        }
        int hops = (run.owner() instanceof OwnerRef.OfMessage answering ? answering.hops() : 0) + 1;
        if (hops > MAX_HOPS) {
            return done(round, index, "This turn is itself answering another conversation: do the work here instead of handing "
                    + "it on.", true);
        }
        SessionState session = out.session(run.ref());
        if (session.id().equals(parsed.session())) {
            return done(round, index, "\"session\" is this conversation itself.", true);
        }
        // 这一轮不是用户开的（是别的会话的回话让会话自己开的）：往外交活有上限
        boolean alone = run.owner() instanceof OwnerRef.OfSession && session.unattended() > 0;
        if (alone && session.unattended() > MAX_UNATTENDED) {
            return done(round, index, "Not handed off: this conversation has handed off " + MAX_UNATTENDED + " pieces of work on its own "
                    + "since the user last spoke, which is the limit. Hand-offs are possible again after the user's next message.", true);
        }
        long sent = round.slots().stream().filter(each -> DelegateCall.NAME.equals(each.call().name())
                && (each.status() == ToolSlot.Status.ASKED || each.status() == ToolSlot.Status.DONE && !each.result().error())).count();
        if (sent >= MAX_SENT) {
            return done(round, index, "At most " + MAX_SENT + " hand-offs in one turn.", true);
        }
        long asked = round.slots().stream().filter(each -> each.status() == ToolSlot.Status.ASKED).count();
        if (parsed.waits() && asked >= MAX_ASKED) {
            return done(round, index, "At most " + MAX_ASKED + " answers can be waited for at once; use wait=false for the rest.", true);
        }
        CommandId id = out.nextCommandId();
        SessionId to = parsed.session() != null ? parsed.session() : DelegateCall.sessionOf(id);
        // 新会话开在哪个目录：指定了工作区就用它的目录，没指定就和这个会话同一个
        SessionMessage message = new SessionMessage(id, run.ref(), DelegateCall.text(parsed, session.id()), null, false, hops, run.model(),
                parsed.workspace() != null ? parsed.workspace() : session.dir(), session.offRecord() ? Boolean.TRUE : null);
        out.emit(new Send(id, run.ref(), to, message));
        if (alone) {
            out.put(session.withUnattended(session.unattended() + 1));
        }
        if (parsed.waits()) {
            return round.withSlot(index, slot.asked(id));
        }
        return done(round, index, (parsed.session() != null ? "Handed to conversation " : "Opened conversation ") + to.value()
                + (parsed.workspace() != null ? " in " + parsed.workspace() : "")
                + ". It works on its own; its answer comes back here later as a message.", false);
    }

    /**
     * 撤回交给会话 {@code to} 的活：送一句撤回，当场完成。这边不知道那边做到哪了（一步只看自己的会话），所以结果里写明两种可能；
     * 那边还没做完的每一件都会回一句“没做成”，和平时的回话一样回来。
     */
    private static ToolRound takeBack(Outcome out, RunState run, ToolRound round, int index, SessionId to) {
        if (out.session(run.ref()).id().equals(to)) {
            return done(round, index, "\"session\" is this conversation itself.", true);
        }
        CommandId id = out.nextCommandId();
        out.emit(new Send(id, run.ref(), to, SessionMessage.withdrawal(id, run.ref())));
        return done(round, index, "Told conversation " + to.value() + " to stop what this conversation handed it. Work it was still doing "
                + "or had queued ends now, and each such piece answers \"did not finish\" as a message. Work it had already finished "
                + "stays as it is, and so does anything the user asked it directly.", false);
    }

    private static ToolRound done(ToolRound round, int index, String text, boolean error) {
        ToolSlot slot = round.slots().get(index);
        return round.withSlot(index, slot.done(new ToolResultBlock(slot.call().id(), text, error, null)));
    }

    /** 只剩等回话的槽位：进 {@link AwaitingReply}，期限记成绝对时刻，定时器是命令。已经在等时不重起定时器。 */
    static void await(Outcome out, RunState run) {
        if (run.phase() instanceof AwaitingReply) {
            out.put(run);
            return;
        }
        CommandId timer = out.nextCommandId();
        out.emit(new StartTimer(timer, run.ref(), LIMIT));
        out.enter(run, new AwaitingReply(out.envelope.at().plus(LIMIT), timer));
    }

    /** 期限到：还在等的槽位得到“没等到”。对方不受影响，它晚到的回话当一条消息处理。别的定时器忽略。 */
    public static Decision expired(StepContext context) {
        TimerFired event = (TimerFired) context.envelope().event();
        RunState run = context.run();
        if (!(run.phase() instanceof AwaitingReply waiting) || !event.command().equals(waiting.timer())) {
            return Decision.unchanged(context.state());
        }
        Outcome out = new Outcome(context);
        ToolRound round = run.tools();
        for (int i = 0; i < round.slots().size(); i++) {
            if (round.slots().get(i).status() == ToolSlot.Status.ASKED) {
                round = done(round, i, "No answer within " + LIMIT.toMinutes() + " minutes. The other conversation may still be working; "
                        + "its answer will come back later as a message. For long work use wait=false.", true);
            }
        }
        ToolSteps.dispatch(out, run.withTools(round));
        return out.decision();
    }

    // ---- 听话的一方 ----

    /** 会话来话到了。发的人必须是同一个用户。 */
    public static Decision received(StepContext context) {
        EventEnvelope envelope = context.envelope();
        SessionMessage event = (SessionMessage) envelope.event();
        RunState run = context.run();
        if (!event.from().principal().equals(envelope.principal())) {
            RunRef where = run != null ? run.ref() : new RunRef(envelope.principal(), envelope.session(), RunId.of(envelope.session(), envelope.seq()));
            Outcome out = new Outcome(context);
            out.report(List.of(new HookReported(where, KERNEL, "message " + event.id().value() + " ignored: it comes from another user")));
            return out.decision();
        }
        SessionState known = context.state().sessions().get(envelope.session());
        if (known != null && !known.principal().equals(envelope.principal())) {
            // 这个会话是别的用户的（会话编号全服务器唯一）：处理器本来就不该送进来，这里再挡一次，状态不动、不回确认
            return Decision.unchanged(context.state());
        }
        // 重送来的（编号见过）：只回确认，别的什么都不做。确认和记编号在 Outcome 里统一做
        if (known != null && known.inbox().hasSeen(event.id())) {
            return new Outcome(context).decision();
        }
        return event.isWithdrawal() ? withdrawn(context, event) : event.isWaitingForUser() ? waiting(context, event)
                : event.isReply() ? reply(context, event) : request(context, event);
    }

    /**
     * 撤回到了：那个会话交来的活，排着队还没开始的去掉并各回一句“没做成”；正在做的那一轮按被取消结束（收尾时照常把“没做成”
     * 送回去）。别的会话交来的、用户自己在这里问的都不动。什么都对不上（已经做完了）就什么都不做，只回确认。
     */
    private static Decision withdrawn(StepContext context, SessionMessage event) {
        EventEnvelope envelope = context.envelope();
        Outcome out = new Outcome(context);
        SessionState session = out.state().sessions().get(envelope.session());
        if (session == null) {
            return out.decision();
        }
        SessionId from = event.from().session();
        RunRef where = new RunRef(envelope.principal(), envelope.session(), RunId.of(envelope.session(), envelope.seq()));
        List<SessionMessage> kept = new ArrayList<>();
        for (SessionMessage waiting : session.inbox().waiting()) {
            if (!waiting.from().session().equals(from)) {
                kept.add(waiting);
                continue;
            }
            CommandId id = out.nextCommandId();
            out.emit(new Send(id, where, from, new SessionMessage(id, where, "it was " + DelegateCall.WITHDRAWN + " before it started.",
                    waiting.id(), true, waiting.hops(), null, null, null)));
        }
        if (kept.size() != session.inbox().waiting().size()) {
            out.put(session.withInbox(new tantalum.value.state.Inbox(kept, session.inbox().unconfirmed(), session.inbox().seen())));
        }
        RunState run = context.run();
        if (run != null && !run.isEnded() && run.owner() instanceof OwnerRef.OfMessage owner && owner.from().equals(from)) {
            CancelSteps.end(out, run, EndReason.CANCELLED, DelegateCall.WITHDRAWN);
        }
        return out.decision();
    }

    /** 对方确认收到了：从“还没确认”里去掉。 */
    public static Decision delivered(StepContext context) {
        EventEnvelope envelope = context.envelope();
        tantalum.value.event.MessageDelivered event = (tantalum.value.event.MessageDelivered) envelope.event();
        SessionState session = context.state().sessions().get(envelope.session());
        if (session == null || session.inbox().confirmed(event.message()).equals(session.inbox())) {
            return Decision.unchanged(context.state());
        }
        Outcome out = new Outcome(context);
        out.put(session.withInbox(session.inbox().confirmed(event.message())));
        return out.decision();
    }

    /** 服务器重启后、或者巡查发现一直没确认：还没确认的话照原来的编号再送一次。 */
    public static Decision redeliver(StepContext context) {
        EventEnvelope envelope = context.envelope();
        SessionState session = context.state().sessions().get(envelope.session());
        if (session == null || session.inbox().unconfirmed().isEmpty()) {
            return Decision.unchanged(context.state());
        }
        Outcome out = new Outcome(context);
        RunRef here = new RunRef(envelope.principal(), envelope.session(), RunId.of(envelope.session(), envelope.seq()));
        tantalum.value.state.Inbox inbox = session.inbox();
        for (tantalum.value.state.Inbox.Outgoing outgoing : session.inbox().unconfirmed()) {
            // “在等人”只在还等着的时候有用：那一轮已经不在等用户了（答了、结束了），这句就不用再送，从信箱里去掉。
            // 旧账里的也走这里：加这种话之前记下的等人，回放时会多出一句从没送过的“在等人”，不该在很久以后突然送出去。
            if (outgoing.message().isWaitingForUser() && !(context.state().runs().get(outgoing.message().from().run()) instanceof RunState asking
                    && asking.phase() instanceof tantalum.value.state.AwaitingAnswer)) {
                inbox = inbox.confirmed(outgoing.message().id());
                continue;
            }
            out.emit(new Send(out.nextCommandId(), here, outgoing.to(), outgoing.message()));
        }
        if (!inbox.equals(session.inbox())) {
            out.put(session.withInbox(inbox));
        }
        return out.decision();
    }

    /** 交来的活：空闲就为它开一轮，正忙就排队。转手太多的不接，回一句“不接”。 */
    private static Decision request(StepContext context, SessionMessage event) {
        EventEnvelope envelope = context.envelope();
        Outcome out = new Outcome(context);
        if (event.hops() > MAX_HOPS) {
            CommandId id = out.nextCommandId();
            RunRef where = new RunRef(envelope.principal(), envelope.session(), RunId.of(envelope.session(), envelope.seq()));
            out.emit(new Send(id, where, event.from().session(), new SessionMessage(id, where,
                    "Not taken: this was handed on too many times.", event.id(), true, event.hops(), null, null, null)));
            return out.decision();
        }
        SessionState existing = out.state().sessions().get(envelope.session());
        SessionState session = existing == null ? SessionState.start(envelope.session(), envelope.principal()) : existing;
        if (!session.messages().isEmpty() && session.offRecord() != Boolean.TRUE.equals(event.offRecord())) {
            // 一边“不进记忆”、一边进：不接。不然不进记忆的会话里说的话会经这个会话进了记忆，或者反过来它的回答被带出去
            CommandId id = out.nextCommandId();
            RunRef where = new RunRef(envelope.principal(), envelope.session(), RunId.of(envelope.session(), envelope.seq()));
            out.emit(new Send(id, where, event.from().session(), new SessionMessage(id, where,
                    "Not taken: one of the two conversations is off the record and the other is not. Open a new conversation "
                            + "for this instead (leave out \"session\").", event.id(), true, event.hops(), null, null, null)));
            return out.decision();
        }
        if (session.messages().isEmpty()) {
            // 工作目录、“不进记忆”和用户开的会话一样，只在第一次定下
            if (event.dir() != null) {
                session = session.withDir(event.dir());
            }
            if (Boolean.TRUE.equals(event.offRecord())) {
                session = session.withOffRecord();
            }
        }
        RunState run = context.run();
        if (run != null && !run.isEnded()) {
            out.put(session.withInbox(session.inbox().add(event)));
            return out.decision();
        }
        out.put(session);
        begin(out, event);
        return out.decision();
    }

    /** 为一句交来的话开一轮：追加它（用户角色的消息，但不是用户说的），主人是这句话。 */
    static void begin(Outcome out, SessionMessage message) {
        SubmitSteps.startRun(out, new OwnerRef.OfMessage(message.id(), message.from().session(), message.hops()),
                Message.user(message.text()), message.model());
    }

    /**
     * 为回一句话开的一轮要停下来等用户回答了：告诉说话的会话一声（一轮只说一次），返回记下“说过了”的运行。
     * 别的运行原样返回。问的那句话只取默认文字；它进的是对方会话的模型，不是给人看的界面。
     */
    static RunState tellWaiting(Outcome out, RunState run, tantalum.value.question.Question question) {
        if (!(run.owner() instanceof OwnerRef.OfMessage owner) || owner.toldWaiting()) {
            return run;
        }
        String title = question.title() == null ? "" : question.title().plain();
        String detail = question.detail() == null ? "" : question.detail().plain();
        String asked = (title == null ? "" : title.strip()) + (detail == null || detail.isBlank() ? "" : "\n" + detail.strip());
        CommandId id = out.nextCommandId();
        out.emit(new Send(id, run.ref(), owner.from(), SessionMessage.waitingForUser(id, run.ref(), asked.strip(), owner.message(), owner.hops())));
        return run.withOwner(owner.told());
    }

    /**
     * “在等人”到了：交出去的那件活停在等用户回答。正等着它回话的那次调用当场得到“它在等用户”，这一轮接着走（好去告诉用户）；
     * 没在等的当一条消息（空闲就开一轮，正在跑就作为插话）。那件活的回话以后照常来，那时已经没有槽位在等，按消息处理。
     */
    private static Decision waiting(StepContext context, SessionMessage event) {
        EventEnvelope envelope = context.envelope();
        Outcome out = new Outcome(context);
        RunState run = context.run();
        String from = event.from().session().value();
        String asked = event.text().strip();
        // 只陈述事实，怎么跟用户说由模型定
        String told = "Conversation " + from + " has stopped to ask the user" + (asked.isEmpty() ? " something. " : ":\n" + asked + "\n")
                + "It waits there until the user answers in that conversation; it cannot be answered from here. "
                + "Its result comes back here as a message when it is done.";
        if (run != null && !run.isEnded() && run.tools() != null) {
            ToolRound round = run.tools();
            for (int i = 0; i < round.slots().size(); i++) {
                ToolSlot slot = round.slots().get(i);
                if (slot.status() == ToolSlot.Status.ASKED && event.answers().equals(slot.command())) {
                    RunState next = run.withTools(done(round, i, told, false));
                    boolean more = next.tools().slots().stream().anyMatch(each -> each.status() == ToolSlot.Status.ASKED);
                    if (!more && run.phase() instanceof AwaitingReply awaiting && awaiting.timer() != null) {
                        out.emit(new AbortCommands(out.nextCommandId(), run.ref(), Set.of(awaiting.timer())));
                    }
                    ToolSteps.dispatch(out, next);
                    return out.decision();
                }
            }
        }
        SessionState session = out.state().sessions().get(envelope.session());
        if (session == null) {
            return out.decision();
        }
        Message message = Message.user(DelegateCall.REPORT + told);
        if (run == null || run.isEnded()) {
            SubmitSteps.attended(out, false);
            SubmitSteps.startRun(out, new OwnerRef.OfSession(session.id()), message, null);
        } else {
            List<Message> steers = new ArrayList<>(run.pendingSteers());
            steers.add(message);
            out.put(run.withPendingSteers(steers));
        }
        return out.decision();
    }

    /** 回话：对得上一个在等的槽位就写成它的结果；否则当一条消息，会话空闲就开一轮让模型处理，正在跑就作为插话。 */
    private static Decision reply(StepContext context, SessionMessage event) {
        EventEnvelope envelope = context.envelope();
        Outcome out = new Outcome(context);
        RunState run = context.run();
        String from = event.from().session().value();
        String text = event.text().strip();
        if (run != null && !run.isEnded() && run.tools() != null) {
            ToolRound round = run.tools();
            for (int i = 0; i < round.slots().size(); i++) {
                ToolSlot slot = round.slots().get(i);
                if (slot.status() == ToolSlot.Status.ASKED && event.answers().equals(slot.command())) {
                    String result = event.failed() ? "Conversation " + from + " did not finish: " + text
                            : text.isEmpty() ? "Conversation " + from + " ended without an answer." : "Answer from conversation " + from + ":\n" + text;
                    RunState next = run.withTools(done(round, i, result, event.failed() || text.isEmpty()));
                    boolean more = next.tools().slots().stream().anyMatch(each -> each.status() == ToolSlot.Status.ASKED);
                    if (!more && run.phase() instanceof AwaitingReply waiting && waiting.timer() != null) {
                        out.emit(new AbortCommands(out.nextCommandId(), run.ref(), Set.of(waiting.timer())));
                    }
                    ToolSteps.dispatch(out, next);
                    return out.decision();
                }
            }
        }
        SessionState session = out.state().sessions().get(envelope.session());
        if (session == null) {
            // 没有这个会话：没什么可做，但照样回确认，免得对方一直留着重送
            return out.decision();
        }
        Message message = Message.user(DelegateCall.REPORT + "Conversation " + from
                + (event.failed() ? " did not finish: " + text : text.isEmpty() ? " ended without an answer." : " finished:\n" + text));
        if (run == null || run.isEnded()) {
            // 没有人说话，会话自己开一轮：从这里起往外交活要算数（见 MAX_UNATTENDED）
            SubmitSteps.attended(out, false);
            SubmitSteps.startRun(out, new OwnerRef.OfSession(session.id()), message, null);
        } else {
            List<Message> steers = new ArrayList<>(run.pendingSteers());
            steers.add(message);
            out.put(run.withPendingSteers(steers));
        }
        return out.decision();
    }

    /**
     * 为回一句话而开的一轮结束了：把结果作为一条回话送回说话的会话。完成的送最后的回答；没做成的写明是失败、被取消、被重启打断
     * 还是被停下。主人是会话自己的一轮什么都不送。
     */
    static void answer(Outcome out, RunState run, SessionState session, EndReason reason, String detail) {
        if (!(run.owner() instanceof OwnerRef.OfMessage owner)) {
            return;
        }
        boolean failed = reason != EndReason.COMPLETED;
        String text = switch (reason) {
            case COMPLETED -> lastAnswer(session);
            case FAILED -> "it failed" + (detail == null ? "." : ": " + detail);
            case STOPPED -> "it was stopped" + (detail == null ? "." : ": " + detail);
            case CANCELLED -> CancelSteps.INTERRUPTED_BY_RESTART.equals(detail)
                    ? "it was interrupted by a server restart. Its history is kept: hand the work to it again (session=\""
                            + session.id().value() + "\") to let it go on."
                    : "it was cancelled" + (detail == null ? "." : ": " + detail);
        };
        CommandId id = out.nextCommandId();
        out.emit(new Send(id, run.ref(), owner.from(), new SessionMessage(id, run.ref(), text, owner.message(), failed, owner.hops(),
                null, null, null)));
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
}
