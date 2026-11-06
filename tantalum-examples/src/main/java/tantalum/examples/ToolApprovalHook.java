package tantalum.examples;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import tantalum.kernel.hook.KernelHook;
import tantalum.value.event.WorkDone;
import tantalum.value.hook.Allow;
import tantalum.value.hook.HookContext;
import tantalum.value.hook.HookResult;
import tantalum.value.hook.NeedWork;
import tantalum.value.hook.Stop;
import tantalum.value.json.Json;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.state.KernelState;
import tantalum.value.state.SessionState;
import tantalum.value.text.LocalText;

/**
 * 工具审批（{@code beforeToolCall} + {@code onWorkResult}）：调用列出的工具前在对话页上问用户（{@link ApprovalWork}），
 * 用 {@code onWorkResult} 看回答，还没回答就再要一轮工作。
 *
 * <p>“这个会话里一直允许”：用户选了它，{@link ApprovalWork} 的结果带一条记录，内核把它记进会话状态
 * （{@code SessionState.note("approval")}，来自 journal 里的 {@code WorkDone}），这里读到就直接放行；别的会话照样问。
 * 允许名单只来自事件，钩子仍是纯函数，恢复和重放结果相同。参照：DSH 的工具审批只有一次性的“允许这一次”和整个会话的策略开关
 * （{@code packages/interaction/user-approval/README.md:165}，策略记成会话事件 {@code approval/policy}，{@code src/index.ts:31-47}）；
 * 按对象记“这个会话里允许”的是它的电脑操作插件（{@code packages/experimental/cua/src/application-access.ts:154-182}，按应用记、存在内存）；
 * pi 的 {@code examples/extensions/permission-gate.ts:25} 每次都问“Yes / No”，什么都不记。我们按工具名记、记进会话事件，
 * 拒绝时告诉模型不要换个工具绕过去（同 DSH {@code application-access.ts:299-301}）。
 *
 * <p>结果：列出的工具 → {@code NeedWork}；这个会话里一直允许的 → 放行；同意 → 放行；拒绝、工作失败、问了 {@code maxPolls} 次没人回答 →
 * {@code Stop}，只拦这一个调用；待定 → 再要一轮。默认关（策略插件），开着时出错按拦截处理。
 */
public final class ToolApprovalHook implements KernelHook {

    private final Set<String> tools;
    private final int maxPolls;

    /** {@code maxPolls} 是一个调用最多问几次；它要小于内核的工作轮数上限（默认 8），否则先撞上限，整个运行失败。 */
    public ToolApprovalHook(Set<String> tools, int maxPolls) {
        if (maxPolls < 1) {
            throw new IllegalArgumentException("maxPolls must be at least 1 but was " + maxPolls);
        }
        this.tools = Set.copyOf(tools);
        this.maxPolls = maxPolls;
    }

    @Override
    public String name() {
        return "toolApproval";
    }

    @Override
    public LocalText description() {
        // 例子插件示范带三种语言的写法：控制台按看的人的语言选，没有就用英文（console-i18n I10）
        return LocalText.en("Before a listed tool is called, asks you on the conversation page: allow once, always allow in this session, or deny.")
                .zh("调用列出的工具前先在对话页上问你，可以只允许这一次、这个会话里一直允许，或者拒绝。")
                .ja("リストにあるツールを呼び出す前に、会話ページで確認します。今回だけ許可、このセッションでは常に許可、または拒否を選べます。");
    }

    /** 策略插件：默认关，用户在插件页打开（管理员要对所有人强制开启时写进 {@code tantalum.hooks.required}）；开着时出错按拦截处理。 */
    @Override
    public boolean defaultEnabled() {
        return false;
    }

    @Override
    public boolean failClosed() {
        return true;
    }

    @Override
    public HookResult beforeToolCall(KernelState state, HookContext context) {
        ToolCallBlock call = context.call();
        if (call == null || !tools.contains(call.name()) || allowedInSession(state, context, call.name())) {
            return Allow.PLAIN;
        }
        return ask(new Json.Str(call.name()), call.arguments() == null ? new Json.Null() : call.arguments(), new Json.Str(call.id()), 0);
    }

    /** 这个会话里用户选过“一直允许”的工具。 */
    private static boolean allowedInSession(KernelState state, HookContext context, String tool) {
        SessionState session = state.sessions().get(context.run().session());
        return session != null && session.note(ApprovalWork.TYPE).fields().get(tool) instanceof Json.Bool allowed && allowed.value();
    }

    /** 每个工作做完都会来这里，只看自己的审批工作。失败的结果里没有原来的输入，所以工具、参数、调用和次数由 {@link ApprovalWork} 带回来。 */
    @Override
    public HookResult onWorkResult(KernelState state, HookContext context) {
        WorkDone work = context.work();
        if (work == null || !ApprovalWork.TYPE.equals(work.workType())) {
            return Allow.PLAIN;
        }
        if (work.failed()) {
            return new Stop("approval failed: " + text(work.result()));
        }
        Map<String, Json> reply = work.result() instanceof Json.Obj obj ? obj.fields() : Map.of();
        String decision = reply.get("decision") instanceof Json.Str value ? value.value() : "";
        String tool = reply.get("tool") instanceof Json.Str value ? value.value() : "this tool";
        return switch (decision) {
            case ApprovalWork.APPROVED -> Allow.PLAIN;
            case ApprovalWork.DENIED -> new Stop("the user denied this call to " + tool
                    + ". Do not retry it or use another tool to get around this decision.");
            case ApprovalWork.PENDING -> pending(reply);
            default -> new Stop("approval gave no decision: " + text(work.result()));
        };
    }

    private HookResult pending(Map<String, Json> reply) {
        int poll = reply.get("poll") instanceof Json.Num number ? number.value().intValue() : maxPolls;
        if (poll + 1 >= maxPolls) {
            return new Stop("no approval decision after " + maxPolls + " polls");
        }
        return ask(reply.getOrDefault("tool", new Json.Str("")), reply.getOrDefault("arguments", new Json.Null()),
                reply.getOrDefault("call", new Json.Str("")), poll + 1);
    }

    /** 键按固定顺序放：输入会写进 journal，回放要逐字节相同。 */
    private static NeedWork ask(Json tool, Json arguments, Json call, int poll) {
        Map<String, Json> input = new LinkedHashMap<>();
        input.put("tool", tool);
        input.put("arguments", arguments);
        input.put("call", call);
        input.put("poll", new Json.Num(BigDecimal.valueOf(poll)));
        return new NeedWork(ApprovalWork.TYPE, new Json.Obj(input));
    }

    private static String text(Json result) {
        return result instanceof Json.Str value ? value.value() : String.valueOf(result);
    }
}
