package tantalum.kernel.step;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import tantalum.kernel.policy.InputPolicy;
import tantalum.kernel.table.StepContext;
import tantalum.value.command.AssembleContext;
import tantalum.value.decision.Decision;
import tantalum.value.event.Assignment;
import tantalum.value.event.Delivery;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.Input;
import tantalum.value.event.Report;
import tantalum.value.event.Root;
import tantalum.value.event.SkillUse;
import tantalum.value.event.UserPrompt;
import tantalum.value.id.CommandId;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;
import tantalum.value.model.Message;
import tantalum.value.model.ModelChoice;
import tantalum.value.model.SkillBlock;
import tantalum.value.state.EndReason;
import tantalum.value.state.HelperOf;
import tantalum.value.state.InboxItem;
import tantalum.value.state.RunCause;
import tantalum.value.state.RunState;
import tantalum.value.state.SessionState;

/**
 * 所有输入的一个处理函数，和会话的收件队列。
 *
 * <p>一条输入到了：照 {@link InputPolicy} 那张表看收不收、怎么办，然后进队。会话空闲就从队首开一轮；有一轮在跑，
 * 标着“可并入”的在下一次调模型前并进历史，其余的等这一轮结束。一轮怎么结束，没被模型看到的输入都还在队里，
 * 由 {@link #unseen} 决定它们是只记进历史还是接着处理。
 */
public final class InboxSteps {

    private InboxSteps() {}

    /** 一条输入到了。会话还没建、不是这个人的、这种会话不收这种输入：什么都不改。 */
    public static Decision arrived(StepContext context) {
        Input input = (Input) context.envelope().event();
        EventEnvelope envelope = context.envelope();
        SessionState session = context.state().sessions().get(envelope.session());
        if (session == null || !session.principal().equals(envelope.principal())) {
            return Decision.unchanged(context.state());
        }
        boolean join = input instanceof UserPrompt prompt && prompt.delivery() == Delivery.JOIN;
        InputPolicy.Rule rule = InputPolicy.of(session.kind(), input.kind(), join);
        if (!rule.accepted()) {
            return Decision.unchanged(context.state());
        }
        Outcome out = new Outcome(context);
        boolean taken = switch (input) {
            case UserPrompt prompt -> prompt(out, session, prompt, rule.handling());
            case Assignment assignment -> assignment(out, session, assignment);
            case Report report -> AssignmentSteps.reported(out, session, report);
        };
        return taken ? out.decision() : Decision.unchanged(context.state());
    }

    /** 你说的一句话。人说话了，两个“没人说话”的计数清零。 */
    private static boolean prompt(Outcome out, SessionState before, UserPrompt prompt, InputPolicy.Handling handling) {
        attended(out, before.id());
        SessionState session = out.state().sessions().get(before.id());
        Message message = Message.user(prompt.text(), prompt.attachments(), blocks(prompt.skills()), prompt.attached());
        boolean busy = busy(out, session);
        boolean join = handling == InputPolicy.Handling.JOIN;
        // 并进去的不带模型选择：正在跑的那一轮模型不变；空闲时为它开的一轮用会话现在的
        out.put(session.enqueue(new InboxItem(out.envelope.seq(), Input.Kind.PROMPT, Root.USER, message, join ? null : prompt.model(),
                join && busy, null)));
        if (!busy) {
            next(out, session.id());
        }
        return true;
    }

    /** 帮手只收建它的那一条交代；已经为它开过一轮、或者它已经在队里，是重复送达。 */
    private static boolean assignment(Outcome out, SessionState session, Assignment assignment) {
        HelperOf helper = session.helper();
        if (helper == null || !helper.assignment().equals(assignment.id()) || !helper.issuer().equals(assignment.issuer())
                || session.lastRun() != null || !session.inbox().isEmpty()) {
            return false;
        }
        out.put(session.enqueue(new InboxItem(out.envelope.seq(), Input.Kind.ASSIGNMENT, assignment.root(),
                Message.assignment(assignment.id(), assignment.prompt()), assignment.model(), false, assignment.id())));
        next(out, session.id());
        return true;
    }

    /** 点名的技能里读到内容的那些变成消息里的块，按点名的顺序；没读到的不进消息，只留在事件里给对话页看。 */
    private static List<SkillBlock> blocks(List<SkillUse> skills) {
        return skills.stream().filter(SkillUse::loaded).map(skill -> new SkillBlock(skill.name(), skill.content())).toList();
    }

    /** 人说话了：无人值守的两个计数清零。你回答问题也算（{@code AnswerSteps}）。 */
    static void attended(Outcome out, SessionId id) {
        SessionState session = out.state().sessions().get(id);
        if (session != null && session.assignments().attended() != session.assignments()) {
            out.put(session.withAssignments(session.assignments().attended()));
        }
    }

    /** 会话里有没有一轮在跑。 */
    static boolean busy(Outcome out, SessionState session) {
        RunState run = session.activeRun() == null ? null : out.state().runs().get(session.activeRun());
        return run != null && !run.isEnded();
    }

    /** 队里有没有等着并进这一轮的。有的话，模型这次不要工具了也得再调一次。 */
    static boolean joinable(SessionState session) {
        return session.inbox().stream().anyMatch(InboxItem::join);
    }

    /**
     * 调模型之前：把队里标着“可并入”的按先后并进历史，这一轮的 {@code root} 降到它们里面最低的。返回写回后的运行。
     */
    static RunState absorb(Outcome out, RunState run) {
        SessionState session = out.session(run.ref());
        if (!joinable(session)) {
            return run;
        }
        List<InboxItem> kept = new ArrayList<>();
        SessionState grown = session;
        RunCause cause = run.cause();
        for (InboxItem item : session.inbox()) {
            if (item.join()) {
                grown = grown.append(item.message());
                cause = cause.absorbing(item.root());
            } else {
                kept.add(item);
            }
        }
        out.put(grown.withInbox(kept));
        RunState absorbed = cause == run.cause() ? run : run.withCause(cause);
        out.put(absorbed);
        return absorbed;
    }

    /**
     * 一轮要结束了：队里还标着“可并入”的，是这一轮没来得及让模型看到的。
     * <ul>
     *   <li>你并进去的话：进历史，下一轮看得到，不为它再开一轮。</li>
     *   <li>结果：你按了取消，只进历史；失败或被重启打断，留在队里，由 {@link #next} 再开一轮。</li>
     *   <li>这一轮是手动压缩（它不调模型）：都留在队里，压缩完接着处理。</li>
     * </ul>
     */
    static void unseen(Outcome out, RunState run, EndReason reason, String detail) {
        SessionState session = out.session(run.ref());
        if (!joinable(session)) {
            return;
        }
        boolean compaction = run.cause().startedBy() == RunCause.StartedBy.COMPACTION;
        boolean byUser = reason == EndReason.CANCELLED && detail == null;
        List<InboxItem> kept = new ArrayList<>();
        SessionState grown = session;
        for (InboxItem item : session.inbox()) {
            if (!item.join()) {
                kept.add(item);
            } else if (compaction || item.kind() == Input.Kind.REPORT && !byUser) {
                kept.add(item.withJoin(false));
            } else {
                grown = grown.append(item.message());
            }
        }
        out.put(grown.withInbox(kept));
    }

    /**
     * 会话空闲了：队首是什么就开什么样的一轮。队首连着的几条结果一起带上，算一轮不是人发起的运行；
     * 这样的轮数到了上限，结果只记进历史、不开运行，等人说话。
     */
    static void next(Outcome out, SessionId id) {
        while (true) {
            SessionState session = out.state().sessions().get(id);
            List<InboxItem> inbox = session.inbox();
            if (inbox.isEmpty()) {
                return;
            }
            InboxItem head = inbox.getFirst();
            switch (head.kind()) {
                case PROMPT -> {
                    startRun(out, session.withInbox(inbox.subList(1, inbox.size())), RunCause.prompt(), List.of(head.message()), head.model());
                    return;
                }
                case ASSIGNMENT -> {
                    startRun(out, session.withInbox(inbox.subList(1, inbox.size())),
                            new RunCause(RunCause.StartedBy.ASSIGNMENT, head.root(), head.assignment()), List.of(head.message()), head.model());
                    return;
                }
                case REPORT -> {
                    if (session.assignments().unattendedRuns() >= out.assignmentLimits.maxUnattendedRuns()) {
                        out.put(session.withInbox(inbox.subList(1, inbox.size())).append(head.message()));
                        continue;
                    }
                    int end = 0;
                    Root root = head.root();
                    List<Message> messages = new ArrayList<>();
                    while (end < inbox.size() && inbox.get(end).kind() == Input.Kind.REPORT) {
                        root = Root.lower(root, inbox.get(end).root());
                        messages.add(inbox.get(end).message());
                        end++;
                    }
                    SessionState taken = session.withInbox(inbox.subList(end, inbox.size()))
                            .withAssignments(session.assignments().ranUnattended());
                    startRun(out, taken, new RunCause(RunCause.StartedBy.REPORT, root, null), messages, null);
                    return;
                }
            }
        }
    }

    /**
     * 新运行：追加开这一轮的消息，请求组装上下文。{@code requested} 是开它的那条输入带的模型选择，
     * 和会话现在的选择一起交给 shell 定这次用哪个模型。
     */
    static RunState startRun(Outcome out, SessionState session, RunCause cause, List<Message> messages, ModelChoice requested) {
        SessionState grown = session;
        for (Message message : messages) {
            grown = grown.append(message);
        }
        RunState run = newRun(out, grown, cause);
        CommandId id = out.nextCommandId();
        out.emit(new AssembleContext(id, run.ref(), requested, session.model()));
        return out.await(run, ContextSteps.AWAITING_CONTEXT, Set.of(id));
    }

    /** 在会话上登记一个新运行并写回两者。运行 ID 由事件序号算出，回放时相同。 */
    static RunState newRun(Outcome out, SessionState session, RunCause cause) {
        EventEnvelope envelope = out.envelope;
        RunRef ref = new RunRef(envelope.principal(), envelope.session(), RunId.of(envelope.session(), envelope.seq()));
        out.put(session.startRun(ref.run()));
        RunState run = RunState.start(ref, cause);
        out.put(run);
        return run;
    }
}
