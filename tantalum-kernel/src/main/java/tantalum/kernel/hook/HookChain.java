package tantalum.kernel.hook;

import tantalum.value.text.Failures;

import java.util.ArrayList;
import java.util.List;
import tantalum.value.decision.Fact;
import tantalum.value.decision.HookDecided;
import tantalum.value.decision.HookReported;
import tantalum.value.hook.Allow;
import tantalum.value.hook.Ask;
import tantalum.value.hook.HookContext;
import tantalum.value.hook.HookPoint;
import tantalum.value.hook.HookResult;
import tantalum.value.hook.NeedWork;
import tantalum.value.hook.PromptPatch;
import tantalum.value.hook.Stop;
import tantalum.value.model.ToolSpec;
import tantalum.value.state.KernelState;
import tantalum.value.state.SessionContext;
import tantalum.value.state.SessionState;

/** 按注册顺序遍历钩子，并执行异常规则：{@code failClosed} 的钩子和 {@code beforeToolCall} 失败时关闭，其余跳过并上报；
 * 返回 null 违反契约，一律按 {@code Stop} 处理。每个钩子的决定报一条 {@link HookDecided}，只放行、没有补丁的不报。 */
public final class HookChain {

    private final List<KernelHook> hooks;

    /** 钩子出错时拦不拦只看它自己的 {@link KernelHook#failClosed()}：管理员要求必须开启的插件由 shell 包成 fail-closed 再交进来。 */
    public HookChain(List<KernelHook> hooks) {
        this.hooks = List.copyOf(hooks);
    }

    public HookOutcome run(HookPoint point, KernelState state, HookContext context) {
        StringBuilder system = new StringBuilder();
        List<ToolSpec> tools = new ArrayList<>();
        List<Allow.SessionNote> notes = new ArrayList<>();
        List<Fact> facts = new ArrayList<>();

        SessionContext session = sessionContext(state, context);
        for (KernelHook hook : hooks) {
            if (session != null && !session.pluginActive(hook.plugin())) {
                continue;
            }
            HookResult result;
            try {
                result = call(hook, point, state, context);
            } catch (RuntimeException failure) {
                // 只记类名：消息可能带路径或密钥，事实进 journal 和推送
                facts.add(new HookReported(context.run(), hook.name(), Failures.describe(failure)));
                if (hook.failClosed() || point == HookPoint.BEFORE_TOOL_CALL) {
                    return stopped(new Stop("hook failed: " + hook.name()), facts, hook.name(), point, context);
                }
                continue;
            }
            if (result == null) {
                facts.add(new HookReported(context.run(), hook.name(), "hook returned null"));
                return stopped(new Stop("hook returned null: " + hook.name()), facts, hook.name(), point, context);
            }
            HookDecided decided = decided(hook.name(), point, context, result);
            if (decided != null) {
                facts.add(decided);
            }
            if (!(result instanceof Allow allow)) {
                return new HookOutcome(result, facts, hook.name());
            }
            system.append(allow.patch().systemAppend());
            tools.addAll(allow.patch().addTools());
            notes.addAll(allow.notes());
        }
        return new HookOutcome(new Allow(new PromptPatch(system.toString(), tools), notes), facts, null);
    }

    /**
     * 只跑这个会话开着的插件。名单是运行开始组装上下文时记下的，运行中途不变：中途关掉的插件本次运行照跑
     * （需求 8 第 5 条）。会话还没组装过上下文、或是加名单之前的会话，名单为空，全部照跑。
     */
    private static SessionContext sessionContext(KernelState state, HookContext context) {
        SessionState session = context.run() == null ? null : state.sessions().get(context.run().session());
        return session == null ? null : session.context();
    }

    /** 钩子出错让链拦截时，在 HookReported 之后再报一条 STOP：记录表要能看出这一步是被谁拦下的。 */
    private static HookOutcome stopped(Stop stop, List<Fact> facts, String hook, HookPoint point, HookContext context) {
        facts.add(decided(hook, point, context, stop));
        return new HookOutcome(stop, facts, hook);
    }

    /** 补丁只记长度和工具名；只放行、没有补丁时返回 null。 */
    private static HookDecided decided(String hook, HookPoint point, HookContext context, HookResult result) {
        String toolCall = point == HookPoint.BEFORE_TOOL_CALL && context.call() != null ? context.call().id() : null;
        return switch (result) {
            case Stop stop -> new HookDecided(context.run(), hook, point, HookDecided.Kind.STOP, stop.reason(),
                    0, List.of(), toolCall, null);
            case NeedWork work -> new HookDecided(context.run(), hook, point, HookDecided.Kind.NEED_WORK, null,
                    0, List.of(), toolCall, work.workType());
            // 问用户：问题 id 放 reason 位（HookDecided.questionId）；不加字段，旧会话重写后的行才和原来相同
            case Ask ask -> new HookDecided(context.run(), hook, point, HookDecided.Kind.ASK, ask.question().id(),
                    0, List.of(), toolCall, null);
            case Allow allow when allow.patch().systemAppend().isEmpty() && allow.patch().addTools().isEmpty() -> null;
            case Allow allow -> new HookDecided(context.run(), hook, point, HookDecided.Kind.PATCH, null,
                    allow.patch().systemAppend().length(), allow.patch().addTools().stream().map(ToolSpec::name).toList(),
                    toolCall, null);
        };
    }

    private static HookResult call(KernelHook hook, HookPoint point, KernelState state, HookContext context) {
        return switch (point) {
            case BEFORE_MODEL_CALL -> hook.beforeModelCall(state, context);
            case BEFORE_TOOL_CALL -> hook.beforeToolCall(state, context);
            case ON_FAILURE -> hook.onFailure(state, context);
            case BEFORE_RUN_END -> hook.beforeRunEnd(state, context);
            case ON_WORK_RESULT -> hook.onWorkResult(state, context);
            case BEFORE_COMPACT -> hook.beforeCompact(state, context);
            case ON_ANSWER -> hook.onAnswer(state, context);
        };
    }
}
