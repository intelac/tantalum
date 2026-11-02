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
import tantalum.value.hook.HookPoint;
import tantalum.value.hook.NeedWork;
import tantalum.value.hook.Stop;
import tantalum.value.id.CommandId;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.Message;
import tantalum.value.model.Role;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolResultBlock;
import tantalum.value.state.RunState;
import tantalum.value.state.ToolRound;
import tantalum.value.state.ToolSlot;

/** 工具轮的处理函数。 */
public final class ToolSteps {

    public static final String AWAITING_TOOLS = "AwaitingTools";

    private ToolSteps() {}

    static void start(Outcome out, RunState run, List<ToolCallBlock> calls) {
        List<ToolSlot> slots = calls.stream()
                .map(call -> ToolSlot.pending(call, isConcurrencySafe(out, call))).toList();
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

    /** 依次过 {@code beforeToolCall}。需要工作时暂停，工作完成后从下一个继续。 */
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
                WorkSteps.start(out, run.withTools(round.withChecked(i)), need, HookPoint.BEFORE_TOOL_CALL);
                return;
            }
        }
        dispatch(out, run.withTools(round.withChecked(round.slots().size())));
    }

    private static ToolRound block(ToolRound round, int index, String reason) {
        ToolCallBlock call = round.slots().get(index).call();
        return round.withSlot(index, ToolSlot.blocked(call, new ToolResultBlock(call.id(), reason, true, null)));
    }

    /** 工作完成后继续检查。{@code blockedReason} 不为空时拦截正在等待的调用。 */
    static void resumeChecks(Outcome out, RunState run, String blockedReason) {
        ToolRound round = run.tools();
        int current = round.checked();
        if (blockedReason != null) {
            round = block(round, current, "Tool call blocked: " + blockedReason);
        }
        checkRemaining(out, run, round.withChecked(current + 1));
    }

    public static Decision toolFinished(StepContext context) {
        ToolFinished event = (ToolFinished) context.envelope().event();
        Outcome out = new Outcome(context);
        RunState run = context.run();
        ToolRound round = run.tools();
        for (int i = 0; i < round.slots().size(); i++) {
            ToolSlot slot = round.slots().get(i);
            if (event.command().equals(slot.command())) {
                ToolResultBlock result = new ToolResultBlock(event.toolCallId(), event.text(), event.error(), null);
                round = round.withSlot(i, slot.done(result));
            }
        }
        dispatch(out, run.withTools(round));
        return out.decision();
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
        out.await(run.withTools(round), AWAITING_TOOLS, sent);
    }
}
