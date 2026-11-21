package tantalum.kernel.step;

import java.util.ArrayList;
import java.util.List;
import tantalum.kernel.hook.HookOutcome;
import tantalum.kernel.table.StepContext;
import tantalum.kernel.tool.ToolPairing;
import tantalum.value.decision.Decision;
import tantalum.value.event.WorkDone;
import tantalum.value.hook.Ask;
import tantalum.value.hook.HookPoint;
import tantalum.value.hook.NeedWork;
import tantalum.value.hook.Stop;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.Message;
import tantalum.value.model.ModelChangeBlock;
import tantalum.value.model.ModelChoice;
import tantalum.value.model.Role;
import tantalum.value.state.EndReason;
import tantalum.value.state.Ended;
import tantalum.value.state.RunState;
import tantalum.value.state.RunCause;
import tantalum.value.state.SessionState;

/** 运行的结束路径：结束前的钩子、工具配对、取下一条 {@code follow-up}。 */
public final class RunEnd {

    /** 换模型前压缩失败时运行的详情，后面接新模型的 {@code 路线/模型}；画面据此写“换到 … 之前压缩失败”。 */
    public static final String SWITCH_COMPACTION_FAILED = "compaction failed before switching to ";

    private RunEnd() {}

    /** 处理函数抛异常时使用，只让该运行失败。 */
    public static Decision fail(StepContext context, RuntimeException failure) {
        Outcome out = new Outcome(context);
        finish(out, context.run(), EndReason.FAILED, failure.toString());
        return out.decision();
    }

    static void beforeEnd(Outcome out, RunState run, WorkDone work) {
        beforeEnd(out, run, work, null);
    }

    /** 模型不再要工具时调用；{@code work} 是刚完成的工作，钩子据此判断是否放行；{@code detail} 随 COMPLETED 记录，如回复被截断。 */
    static void beforeEnd(Outcome out, RunState run, WorkDone work, String detail) {
        if (InboxSteps.joinable(out.session(run.ref()))) {
            ModelSteps.callModel(out, run, work);
            return;
        }
        HookOutcome hook = out.hooks.run(HookPoint.BEFORE_RUN_END, out.state(),
                out.hookContext(run.ref()).withWork(work));
        out.report(hook.facts());
        switch (hook.result()) {
            case Stop stop -> finish(out, run, EndReason.STOPPED, stop.reason());
            case NeedWork need -> WorkSteps.start(out, run, need, hook.decidedBy(), HookPoint.BEFORE_RUN_END);
            case Ask ask -> AnswerSteps.ask(out, run, ask, hook.decidedBy(), HookPoint.BEFORE_RUN_END);
            default -> finish(out, run, EndReason.COMPLETED, detail);
        }
    }

    /**
     * 换到窗口更小的模型之前的压缩没成：这个运行失败，会话的模型退回上一个。已经追加的换模型说明不能删，
     * 再追加一条反方向的，下一次调用时模型看到的说明和实际一致。纯函数，回放结果相同。
     */
    static void switchFailed(Outcome out, RunState run) {
        ModelChoice now = run.model();
        ModelChoice back = run.modelBefore();
        SessionState session = out.session(run.ref());
        out.put(session.append(new Message(Role.SYSTEM, List.of(new ModelChangeBlock(now.label(), back.label())), false)).withModel(back));
        finish(out, run.withModel(now, null), EndReason.FAILED, SWITCH_COMPACTION_FAILED + now.label());
    }

    /**
     * 结束运行，运行唯一的出口。未配对的工具调用补上合成结果；队里这一轮没来得及看的输入由 {@link InboxSteps#unseen} 处理；
     * 这一轮欠着一次交代的结果就在这里交回；然后从队首开下一轮。
     * {@code compacting} 原样保留：在等摘要时结束的运行，迟到的摘要回复据此不进历史。
     */
    static void finish(Outcome out, RunState run, EndReason reason, String detail) {
        if (run.tools() != null && !run.tools().allFinished()) {
            List<ContentBlock> results = new ArrayList<>(ToolPairing.complete(run.tools()));
            out.append(run.ref(), new Message(Role.USER, results, false));
        }
        InboxSteps.unseen(out, run, reason, detail);
        out.enter(run.withTools(null), new Ended(reason, detail));

        SessionState session = out.session(run.ref()).endRun();
        out.put(session);
        // 内存里每个会话只留正在跑的和刚结束的这一轮（live-views）：更早结束的去掉，账里还有，接口读索引或账
        List<tantalum.value.id.RunId> older = out.runs().stream()
                .filter(other -> other.ref().session().equals(session.id()) && other.isEnded() && !other.ref().run().equals(run.ref().run()))
                .map(other -> other.ref().run()).toList();
        older.forEach(out::remove);
        if (run.cause().owes() != null) {
            AssignmentSteps.returned(out, run, reason, detail);
        } else if (session.issuer() != null && !session.offRecord() && run.cause().startedBy() == RunCause.StartedBy.PROMPT) {
            // 主会话开的普通会话里，你直接说的这一轮：给主会话记一笔，只记、不调模型（dispatch 需求 3）
            AssignmentSteps.noticed(out, run, session);
        }
        InboxSteps.next(out, session.id());
    }
}
