package tantalum.kernel.step;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import tantalum.kernel.projection.ContextDiff;
import tantalum.kernel.projection.ContextUpdate;
import tantalum.kernel.table.StepContext;
import tantalum.value.command.AssembleContext;
import tantalum.value.decision.Decision;
import tantalum.value.event.ContextAssembled;
import tantalum.value.id.CommandId;
import tantalum.value.model.Message;
import tantalum.value.model.Role;
import tantalum.value.model.ToolChangeBlock;
import tantalum.value.model.ToolSpec;
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
     * 所有模型相同：会话的 system 和工具固定为第一次组装的结果，历史只追加。工具是服务器上的全集，会话开始时用户没开的发给模型时
     * 标为 deferred。之后可变内容（记忆索引等）有变化时，追加一条用户消息告诉模型；用户开着的工具变了，追加一条系统消息说明
     * 新增和移除了哪些（core-tools C14），钩子插件的名单只记下、不告诉模型。会话开始之后才出现的工具（新连上的 MCP）不在全集里，
     * 要等压缩或新会话。
     */
    private static void freezeContext(Outcome out, RunState run, ContextAssembled event) {
        SessionState session = out.session(run.ref());
        SessionContext frozen = session.context();
        if (frozen == null) {
            out.put(session.withContext(new SessionContext(event.system(), event.tools(), event.compaction(), event.sections(),
                    event.enabledTools(), event.enabledTools(), event.enabledPlugins())));
            return;
        }
        SessionState next = session;
        SessionContext context = frozen;
        String changes = ContextDiff.of(frozen.sections(), event.sections());
        if (!changes.isEmpty()) {
            next = next.append(ContextUpdate.message(changes));
            context = context.withSections(event.sections());
        }
        List<String> all = frozen.tools().stream().map(ToolSpec::name).toList();
        List<String> active = event.enabledTools() == null ? all : all.stream().filter(event.enabledTools()::contains).toList();
        List<String> added = active.stream().filter(name -> !frozen.toolActive(name)).toList();
        List<String> removed = all.stream().filter(name -> frozen.toolActive(name) && !active.contains(name)).toList();
        if (!added.isEmpty() || !removed.isEmpty()) {
            next = next.append(new Message(Role.SYSTEM, List.of(new ToolChangeBlock(added, removed)), false));
        }
        if (!added.isEmpty() || !removed.isEmpty() || !Objects.equals(frozen.activePlugins(), event.enabledPlugins())) {
            context = context.withActive(frozen.activeTools() == null && event.enabledTools() == null ? null : active,
                    event.enabledPlugins());
        }
        if (next != session || context != frozen) {
            out.put(next.withContext(context));
        }
    }

    /** 压缩后重新组装 system 和工具（规则 5），新的记忆索引和 MCP 工具在此生效。 */
    static void reassemble(Outcome out, RunState run) {
        CommandId id = out.nextCommandId();
        out.emit(new AssembleContext(id, run.ref()));
        out.await(run, AWAITING_CONTEXT, Set.of(id));
    }
}
