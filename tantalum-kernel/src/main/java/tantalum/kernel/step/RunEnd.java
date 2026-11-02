package tantalum.kernel.step;

import java.util.ArrayList;
import java.util.List;
import tantalum.kernel.hook.HookChain;
import tantalum.kernel.hook.HookOutcome;
import tantalum.kernel.hook.HookPoint;
import tantalum.kernel.tool.ToolPairing;
import tantalum.kernel.tool.ToolSafety;
import tantalum.value.decision.Decision;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.WorkDone;
import tantalum.value.hook.NeedWork;
import tantalum.value.hook.Stop;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.Message;
import tantalum.value.model.Role;
import tantalum.value.state.EndReason;
import tantalum.value.state.Ended;
import tantalum.value.state.KernelState;
import tantalum.value.state.RunState;
import tantalum.value.state.SessionState;

/** 运行的结束路径：结束前的钩子、工具配对、取下一条 {@code follow-up}。 */
public final class RunEnd {

    private RunEnd() {}

    /** 处理函数抛异常时使用，只让该运行失败。 */
    public static Decision fail(KernelState state, EventEnvelope envelope, HookChain hooks, ToolSafety toolSafety,
                                int maxParallelTools, RunState run, RuntimeException failure) {
        Outcome out = new Outcome(state, envelope, hooks, toolSafety, maxParallelTools);
        finish(out, run, EndReason.FAILED, failure.toString());
        return out.decision();
    }

    static void beforeEnd(Outcome out, RunState run, WorkDone work) {
        beforeEnd(out, run, work, null);
    }

    /** 模型不再要工具时调用；{@code work} 是刚完成的工作，钩子据此判断是否放行；{@code detail} 随 COMPLETED 记录，如回复被截断。 */
    static void beforeEnd(Outcome out, RunState run, WorkDone work, String detail) {
        if (!run.pendingSteers().isEmpty()) {
            ModelSteps.callModel(out, run, work);
            return;
        }
        HookOutcome hook = out.hooks.run(HookPoint.BEFORE_RUN_END, out.state,
                out.hookContext(run.ref()).withWork(work));
        out.report(hook.facts());
        switch (hook.result()) {
            case Stop stop -> finish(out, run, EndReason.STOPPED, stop.reason());
            case NeedWork need -> WorkSteps.start(out, run, need, HookPoint.BEFORE_RUN_END);
            default -> finish(out, run, EndReason.COMPLETED, detail);
        }
    }

    /** 结束运行。未配对的工具调用补上合成结果；模型还没看到的插话追加为用户消息，下一个运行会看到。 */
    static void finish(Outcome out, RunState run, EndReason reason, String detail) {
        if (run.tools() != null && !run.tools().allFinished()) {
            List<ContentBlock> results = new ArrayList<>(ToolPairing.complete(run.tools()));
            out.append(run.ref(), new Message(Role.USER, results, false));
        }
        for (Message steer : run.pendingSteers()) {
            out.append(run.ref(), steer);
        }
        out.enter(run.withTools(null).withPendingSteers(List.of()),
                new Ended(reason, detail));

        SessionState session = out.session(run.ref()).withActiveRun(null);
        out.state = out.state.with(session);
        if (!session.followUps().isEmpty()) {
            String next = session.followUps().getFirst();
            out.state = out.state.with(session.withFollowUps(session.followUps().subList(1, session.followUps().size())));
            ModelSteps.startRun(out, new tantalum.value.state.OwnerRef.OfSession(session.id()), next);
        }
    }
}
