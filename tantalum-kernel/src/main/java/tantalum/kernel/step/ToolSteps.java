package tantalum.kernel.step;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import tantalum.kernel.hook.HookOutcome;
import tantalum.kernel.table.StepContext;
import tantalum.kernel.tool.ToolScheduler;
import tantalum.value.command.RunTool;
import tantalum.value.decision.Decision;
import tantalum.value.event.ToolFinished;
import tantalum.value.hook.Ask;
import tantalum.value.hook.HookPoint;
import tantalum.value.hook.NeedWork;
import tantalum.value.hook.Stop;
import tantalum.value.id.CommandId;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.Message;
import tantalum.value.model.Role;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolResultBlock;
import tantalum.value.question.AskUser;
import tantalum.value.state.RunState;
import tantalum.value.state.ToolRound;
import tantalum.value.state.ToolSlot;

/** 工具轮的处理函数。 */
public final class ToolSteps {

    public static final String AWAITING_TOOLS = "AwaitingTools";

    private ToolSteps() {}

    static void start(Outcome out, RunState run, List<ToolCallBlock> calls) {
        List<ToolSlot> slots = calls.stream()
                .map(call -> AskUser.NAME.equals(call.name()) ? ToolSlot.waiting(call) : ToolSlot.pending(call, isConcurrencySafe(out, call)))
                .toList();
        checkRemaining(out, run, new ToolRound(slots, 0));
    }

    private static boolean isConcurrencySafe(Outcome out, ToolCallBlock call) {
        if (!call.argumentsValid()) {
            return false;
        }
        try {
            return out.toolSafety.isConcurrencySafe(call.name(), call.arguments());
        } catch (RuntimeException failure) {
            return false;
        }
    }

    /** 依次过 {@code beforeToolCall}。需要工作或要问人时暂停，完成后从下一个继续。 */
    private static void checkRemaining(Outcome out, RunState run, ToolRound start) {
        ToolRound round = start;
        for (int i = round.checked(); i < round.slots().size(); i++) {
            ToolCallBlock call = round.slots().get(i).call();
            if (!call.argumentsValid()) {
                round = block(round, i, "Tool arguments are not valid JSON: " + call.rawArguments());
                continue;
            }
            HookOutcome hook = out.hooks.run(HookPoint.BEFORE_TOOL_CALL, out.state(),
                    out.hookContext(run.ref()).withCall(call));
            out.report(hook.facts());
            if (hook.result() instanceof Stop stop) {
                round = block(round, i, "Tool call blocked: " + stop.reason());
            } else if (hook.result() instanceof NeedWork need) {
                WorkSteps.start(out, run.withTools(round.withChecked(i)), need, hook.decidedBy(), HookPoint.BEFORE_TOOL_CALL);
                return;
            } else if (hook.result() instanceof Ask ask) {
                AnswerSteps.ask(out, run.withTools(round.withChecked(i)), ask, hook.decidedBy(), HookPoint.BEFORE_TOOL_CALL);
                return;
            }
        }
        dispatch(out, run.withTools(round.withChecked(round.slots().size())));
    }

    private static ToolRound block(ToolRound round, int index, String reason) {
        ToolCallBlock call = round.slots().get(index).call();
        return round.withSlot(index, ToolSlot.blocked(call, new ToolResultBlock(call.id(), reason, true, null)));
    }

    /**
     * 工作完成后继续检查。{@code blockedReason} 不为空时拦截正在等待的调用。
     * 工具按顺序一个一个检查，这个调用放行或被拦下就换下一个，所以工作轮数在这里清零，每个调用各自计数。
     */
    static void resumeChecks(Outcome out, RunState run, String blockedReason) {
        ToolRound round = run.tools();
        int current = round.checked();
        if (blockedReason != null) {
            round = block(round, current, "Tool call blocked: " + blockedReason);
        }
        checkRemaining(out, run.withWorkRounds(0), round.withChecked(current + 1));
    }

    public static Decision toolFinished(StepContext context) {
        ToolFinished event = (ToolFinished) context.envelope().event();
        Outcome out = new Outcome(context);
        RunState run = context.run();
        ToolRound round = run.tools();
        for (int i = 0; i < round.slots().size(); i++) {
            ToolSlot slot = round.slots().get(i);
            // 只收已发出、还没结果的槽位：重复或迟到的结果不覆盖已有的
            if (event.command().equals(slot.command()) && slot.status() == ToolSlot.Status.SENT) {
                ToolResultBlock result = new ToolResultBlock(event.toolCallId(), event.text(), event.error(), null, event.images());
                round = round.withSlot(i, slot.done(result));
            }
        }
        dispatch(out, run.withTools(round));
        return out.decision();
    }

    /** 用户回答了 {@code ask_user}（或期限到了）：回答就是这个槽位的结果，接着发剩下的。 */
    static void answerSlot(Outcome out, RunState run, String toolCallId, String text) {
        ToolRound round = run.tools();
        for (int i = 0; i < round.slots().size(); i++) {
            ToolSlot slot = round.slots().get(i);
            if (slot.status() == ToolSlot.Status.WAITING && slot.call().id().equals(toolCallId)) {
                round = round.withSlot(i, slot.done(new ToolResultBlock(toolCallId, text, false, null)));
            }
        }
        dispatch(out, run.withTools(round));
    }

    private static void dispatch(Outcome out, RunState pending) {
        RunState run = pending;
        ToolRound round = run.tools();
        if (round.allFinished()) {
            List<ContentBlock> results = new ArrayList<>(round.results());
            out.append(run.ref(), new Message(Role.USER, results, false));
            run = run.withTools(null);
            out.put(run);
            ModelSteps.callModel(out, run, null);
            return;
        }
        for (int index : ToolScheduler.next(round, out.maxParallelTools)) {
            ToolSlot slot = round.slots().get(index);
            CommandId id = out.nextCommandId();
            out.emit(new RunTool(id, run.ref(), slot.call()));
            round = round.withSlot(index, slot.sent(id));
        }
        Set<CommandId> sent = new HashSet<>();
        for (ToolSlot slot : round.slots()) {
            if (slot.status() == ToolSlot.Status.SENT) {
                sent.add(slot.command());
            }
        }
        if (sent.isEmpty()) {
            // 只剩 ask_user 的槽位：按顺序问第一个（一个运行同一时刻只有一个问题，需求 1.6）
            askNext(out, run.withTools(round));
            return;
        }
        out.await(run.withTools(round), AWAITING_TOOLS, sent);
    }

    private static void askNext(Outcome out, RunState run) {
        ToolRound round = run.tools();
        for (int i = 0; i < round.slots().size(); i++) {
            ToolSlot slot = round.slots().get(i);
            if (slot.status() != ToolSlot.Status.WAITING) {
                continue;
            }
            AskUser.Parsed parsed;
            try {
                parsed = AskUser.parse(slot.call().id(), slot.call().arguments());
            } catch (IllegalArgumentException invalid) {
                // 参数不合法：不问人，错误直接给模型
                dispatch(out, run.withTools(round.withSlot(i, slot.done(new ToolResultBlock(slot.call().id(), invalid.getMessage(), true, null)))));
                return;
            }
            AnswerSteps.askFromTool(out, run, parsed.question(), parsed.timeout(), slot.call().id());
            return;
        }
        throw new IllegalStateException("tool round has nothing in flight and nothing waiting: " + round);
    }
}
