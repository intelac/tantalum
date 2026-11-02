package tantalum.kernel.step;

import java.util.Set;
import tantalum.kernel.projection.ContextDiff;
import tantalum.kernel.projection.ContextUpdate;
import tantalum.kernel.table.StepContext;
import tantalum.value.command.AssembleContext;
import tantalum.value.decision.Decision;
import tantalum.value.event.ContextAssembled;
import tantalum.value.id.CommandId;
import tantalum.value.state.RunState;
import tantalum.value.state.SessionContext;
import tantalum.value.state.SessionState;

/** 组装上下文的处理函数：把第一次组装的结果冻结进会话。 */
public final class ContextSteps {

    public static final String AWAITING_CONTEXT = "AwaitingContext";

    private ContextSteps() {}

    public static Decision contextAssembled(StepContext context) {
        ContextAssembled event = (ContextAssembled) context.envelope().event();
        Outcome out = new Outcome(context);
        RunState run = context.run();
        // 压缩刚把上下文清空：这次组装之后的调用不再判断（规则 3.1）。
        SessionState before = out.session(run.ref());
        boolean justCompacted = before.context() == null && before.compaction() != null;
        // 先冻结进会话，之后调模型和判断压缩都从会话取 system 和工具。
        freezeContext(out, run, event);
        ModelSteps.callModel(out, run, null, !justCompacted);
        return out.decision();
    }

    /**
     * 所有模型相同：会话的 system 和工具固定为第一次组装的结果，历史只追加。
     * 之后可变内容（记忆索引等）有变化时，追加一条用户消息告诉模型，新工具要等新会话。
     */
    private static void freezeContext(Outcome out, RunState run, ContextAssembled event) {
        SessionState session = out.session(run.ref());
        SessionContext frozen = session.context();
        if (frozen == null) {
            out.put(session.withContext(new SessionContext(event.system(), event.tools(), event.compaction(), event.sections())));
            return;
        }
        String changes = ContextDiff.of(frozen.sections(), event.sections());
        if (!changes.isEmpty()) {
            out.put(session.append(ContextUpdate.message(changes)).withContext(frozen.withSections(event.sections())));
        }
    }

    /** 压缩后重新组装 system 和工具（规则 5），新的记忆索引和 MCP 工具在此生效。 */
    static void reassemble(Outcome out, RunState run) {
        CommandId id = out.nextCommandId();
        out.emit(new AssembleContext(id, run.ref()));
        out.await(run, AWAITING_CONTEXT, Set.of(id));
    }
}
