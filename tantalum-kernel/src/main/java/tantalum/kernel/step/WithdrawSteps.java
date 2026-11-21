package tantalum.kernel.step;

import java.util.ArrayList;
import java.util.List;
import tantalum.kernel.table.StepContext;
import tantalum.value.decision.Decision;
import tantalum.value.decision.TextSwapped;
import tantalum.value.event.TextWithdrawn;
import tantalum.value.event.TextWithdrawn.Swap;
import tantalum.value.id.RunRef;
import tantalum.value.model.HookTextBlock;
import tantalum.value.model.Message;
import tantalum.value.model.TextSwap;
import tantalum.value.state.Compaction;
import tantalum.value.state.ContextSection;
import tantalum.value.state.InboxItem;
import tantalum.value.state.RunState;
import tantalum.value.state.SessionContext;
import tantalum.value.state.SessionState;
import tantalum.value.state.ToolRound;
import tantalum.value.state.ToolSlot;
import tantalum.value.state.Withdrawal;

/**
 * 撤下文字（{@link TextWithdrawn}）。任何状态下都能做：不加不减消息，不动工具调用和结果的配对，不改运行的走向。
 * <p>历史只追加，这里一个字也不改：撤下的文字记进会话的 {@link SessionState#withdrawn()}，带上当时历史有多少条，
 * {@link SessionState#view()} 给模型看的时候把这之前的消息里逐字出现的原文换掉。撤下以后才说的话不受影响。
 * <p>不在历史里、本来就是整份替换的东西直接换：压缩后的替换段、收件队列里的每一条（排着的、等着并进这一轮的）、
 * 冻结的上下文段和钩子的话；正在跑的一轮里已经回来的工具结果（它们并进历史时就已经是换过的）。
 * <p>只按字面（{@link TextSwap}）。哪些文字该撤下由送事件的一方定，内核不判断。原文哪里都没出现时什么都不改，
 * 所以重复送来没有害处。
 */
public final class WithdrawSteps {

    private WithdrawSteps() {}

    public static Decision withdrawn(StepContext context) {
        TextWithdrawn event = (TextWithdrawn) context.envelope().event();
        SessionState session = context.state().sessions().get(context.envelope().session());
        if (session == null || session.lastRun() == null || !session.principal().equals(context.envelope().principal())) {
            return Decision.unchanged(context.state());
        }
        int places = 0;
        // 历史：一段一段看，在模型现在看到的样子里还出现的才记（已经撤下过的不再记一遍）
        List<Withdrawal> more = new ArrayList<>();
        for (Swap swap : event.swaps()) {
            TextSwap one = new TextSwap(List.of(swap));
            if (one.isEmpty()) {
                continue;
            }
            int found = 0;
            for (int i = 0; i < session.messages().size(); i++) {
                found += one.places(session.said(i));
            }
            if (found > 0) {
                more.add(new Withdrawal(swap.original(), swap.replacement(), session.messages().size(), context.envelope().seq()));
                places += found;
            }
        }
        TextSwap swap = new TextSwap(event.swaps());
        Compaction compaction = session.compaction() == null ? null
                : new Compaction(swap.messages(session.compaction().replacement()), session.compaction().tailFrom());
        List<Message> queued = session.inbox().stream().map(InboxItem::message).toList();
        List<Message> swapped = swap.messages(queued);
        List<InboxItem> inbox = new ArrayList<>(session.inbox().size());
        for (int i = 0; i < swapped.size(); i++) {
            inbox.add(session.inbox().get(i).withMessage(swapped.get(i)));
        }
        SessionContext frozen = context(swap, session.context());
        RunState run = session.activeRun() == null ? null : context.state().runs().get(session.activeRun());
        RunState nextRun = run == null ? null : run(swap, run);
        places += (session.compaction() == null ? 0 : swap.places(session.compaction().replacement())) + swap.places(queued)
                + places(swap, session.context()) + (run == null ? 0 : places(swap, run));
        if (places == 0) {
            return Decision.unchanged(context.state());
        }
        Outcome out = new Outcome(context);
        out.put(session.withdrawing(more, inbox, compaction, frozen));
        if (nextRun != null) {
            out.put(nextRun);
        }
        out.report(List.of(new TextSwapped(new RunRef(session.principal(), session.id(), session.lastRun()), places)));
        return out.decision();
    }

    private static int places(TextSwap swap, SessionContext context) {
        if (context == null) {
            return 0;
        }
        int places = swap.places(context.system());
        for (ContextSection section : context.sections()) {
            places += swap.places(section.body());
        }
        if (context.hooks() != null) {
            for (HookTextBlock hook : context.hooks().texts()) {
                places += swap.places(hook.text());
            }
        }
        return places;
    }

    private static int places(TextSwap swap, RunState run) {
        int places = 0;
        if (run.tools() != null) {
            for (ToolSlot slot : run.tools().slots()) {
                places += slot.result() == null ? 0 : swap.places(slot.result());
            }
        }
        return places;
    }

    private static SessionContext context(TextSwap swap, SessionContext context) {
        if (context == null || places(swap, context) == 0) {
            return context;
        }
        String system = swap.text(context.system());
        List<ContextSection> sections = context.sections().stream()
                .map(section -> new ContextSection(section.name(), swap.text(section.body()), section.byLine())).toList();
        SessionContext.HookState hooks = context.hooks() == null ? null : new SessionContext.HookState(
                context.hooks().texts().stream().map(hook -> new HookTextBlock(hook.hook(), swap.text(hook.text()))).toList(),
                context.hooks().tools(), context.hooks().active());
        return new SessionContext(system, context.tools(), context.compaction(), sections,
                context.initialTools(), context.activeTools(), context.activePlugins(), hooks, context.pluginParams(), context.unlocks());
    }

    private static RunState run(TextSwap swap, RunState run) {
        if (places(swap, run) == 0) {
            return run;
        }
        ToolRound round = run.tools();
        if (round != null) {
            for (int i = 0; i < round.slots().size(); i++) {
                ToolSlot slot = round.slots().get(i);
                if (slot.result() != null) {
                    round = round.withSlot(i, new ToolSlot(slot.call(), slot.status(), slot.concurrencySafe(), slot.command(), swap.result(slot.result())));
                }
            }
        }
        return run.withTools(round);
    }
}
