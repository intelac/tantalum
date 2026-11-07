package tantalum.kernel.step;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import tantalum.kernel.projection.ContextDiff;
import tantalum.kernel.projection.ContextUpdate;
import tantalum.kernel.projection.ToolsGone;
import tantalum.kernel.table.StepContext;
import tantalum.value.command.AssembleContext;
import tantalum.value.decision.Decision;
import tantalum.value.event.ContextAssembled;
import tantalum.value.id.CommandId;
import tantalum.value.model.Message;
import tantalum.value.model.ModelChangeBlock;
import tantalum.value.model.ModelChoice;
import tantalum.value.model.Role;
import tantalum.value.model.ToolChangeBlock;
import tantalum.value.model.ToolSpec;
import tantalum.value.state.CompactionSettings;
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
        // 压缩刚换过视图：这次组装之后的调用不再判断压缩（规则 3.1）。要在冻结上下文之前看。
        boolean justCompacted = out.session(run.ref()).justCompacted();
        // 先定下这个运行的模型（换模型的说明要在工具变化之前），再冻结进会话，之后调模型和判断压缩都从会话取 system 和工具。
        run = chooseModel(out, run, event);
        freezeContext(out, run, event);
        ModelSteps.callModel(out, out.run(run.ref()), null, !justCompacted);
        return out.decision();
    }

    /**
     * 这次用的模型：运行第一次组装上下文时定下，整个运行不变；压缩后重新组装时不再换。路线或模型和会话上一次的
     * 不同时追加一条换模型的说明（只换档位不算，会话没记过模型的旧会话也不算），记下换掉的那个给压缩前的摘要用。
     * 旧事件没有选择，什么都不做。
     */
    private static RunState chooseModel(Outcome out, RunState run, ContextAssembled event) {
        ModelChoice chosen = event.model();
        if (chosen == null || run.model() != null) {
            return run;
        }
        SessionState session = out.session(run.ref());
        ModelChoice previous = session.model();
        boolean switched = previous != null && !previous.sameModel(chosen);
        SessionState next = session;
        if (switched) {
            next = next.append(new Message(Role.SYSTEM, List.of(new ModelChangeBlock(previous.label(), chosen.label())), false));
        }
        if (!chosen.equals(previous)) {
            next = next.withModel(chosen);
        }
        if (next != session) {
            out.put(next);
        }
        RunState updated = run.withModel(chosen, switched ? previous : null);
        out.put(updated);
        return updated;
    }

    /**
     * 所有模型相同：会话的 system 和工具固定为第一次组装的结果，历史只追加。压缩设置每次换成这次算出的那份。工具是服务器上的全集，会话开始时用户没开的发给模型时
     * 标为 deferred。之后可变内容（记忆索引等）有变化时，追加一条用户消息告诉模型；用户开着的工具变了，追加一条系统消息说明
     * 新增和移除了哪些，并重申之前就不能用、现在仍不能用的工具；钩子插件的名单只记下、不告诉模型。
     * 会话开始之后才出现的工具（新连上的 MCP）不在全集里，要等压缩或新会话。
     * 压缩后重新组装时，以前开着过、现在不能用的工具（{@link SessionState#toolsSeen()}）写成一条上下文更新告诉模型：
     * 原来的工具变化消息可能已经压进摘要。
     */
    private static void freezeContext(Outcome out, RunState run, ContextAssembled event) {
        SessionState session = out.session(run.ref());
        SessionContext frozen = session.context();
        if (frozen == null) {
            // 开着的名单只认全集里有的名字，和之后每次组装的算法一样
            List<String> all = event.tools().stream().map(ToolSpec::name).toList();
            List<String> enabled = event.enabledTools() == null ? null : all.stream().filter(event.enabledTools()::contains).toList();
            List<String> active = enabled != null ? enabled : all;
            List<String> gone = session.toolsSeen().stream().filter(name -> !active.contains(name)).toList();
            SessionState next = gone.isEmpty() ? session : session.append(ContextUpdate.message(ToolsGone.text(gone)));
            out.put(next.withContext(new SessionContext(event.system(), event.tools(), event.compaction(), event.sections(),
                    enabled, enabled, event.enabledPlugins())).withToolsSeen(seen(session, active)));
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
            List<String> unavailable = session.toolsSeen().stream()
                    .filter(name -> !active.contains(name) && !removed.contains(name)).toList();
            next = next.append(new Message(Role.SYSTEM, List.of(new ToolChangeBlock(added, removed, unavailable)), false));
        }
        List<String> seen = seen(session, active);
        if (!seen.equals(session.toolsSeen())) {
            next = next.withToolsSeen(seen);
        }
        // 压缩点只决定什么时候压缩，不改请求，换了也不告诉模型。只有带压缩点的新事件换：
        // 之前的事件每次都带当时的配置，但当时只用第一次那份，回放要走同一条路。窗口变成未知（null）时留着原来的。
        CompactionSettings incoming = event.compaction();
        if (incoming != null && incoming.point() > 0 && !incoming.equals(frozen.compaction())) {
            context = context.withCompaction(incoming);
        }
        if (!added.isEmpty() || !removed.isEmpty() || !Objects.equals(frozen.activePlugins(), event.enabledPlugins())) {
            context = context.withActive(frozen.activeTools() == null && event.enabledTools() == null ? null : active,
                    event.enabledPlugins());
        }
        if (next != session || context != frozen) {
            out.put(next.withContext(context));
        }
    }

    /** 开着过的工具并上这次开着的，保持第一次出现的顺序。 */
    private static List<String> seen(SessionState session, List<String> active) {
        if (session.toolsSeen().containsAll(active)) {
            return session.toolsSeen();
        }
        List<String> seen = new ArrayList<>(session.toolsSeen());
        active.stream().filter(name -> !seen.contains(name)).forEach(seen::add);
        return List.copyOf(seen);
    }

    /** 压缩后重新组装 system 和工具（规则 5），新的记忆索引和 MCP 工具在此生效。 */
    static void reassemble(Outcome out, RunState run) {
        CommandId id = out.nextCommandId();
        // 这个运行已经定下的模型照用
        out.emit(new AssembleContext(id, run.ref(), run.model(), out.session(run.ref()).model()));
        out.await(run, AWAITING_CONTEXT, Set.of(id));
    }
}
