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

/**
 * 工具审批（{@code beforeToolCall} + {@code onWorkResult}）：调用列出的工具前先问审批服务，用 {@code onWorkResult} 看审批结果，
 * 还没结果就再要一轮工作。参照 DSH {@code tools/pre-execute} 的 {@code ask}（没人回答就当拒绝，
 * {@code packages/core/tools/src/events.ts:9-11}）和 pi 的 {@code tool_call} 拦截。
 * 结果：列出的工具 → {@code NeedWork}（{@link ApprovalWork}）；同意 → 放行；拒绝、工作失败、查询次数用完 → {@code Stop}，只拦这一个调用；
 * 待定 → 再要一轮。强制钩子：抛异常时这次调用按拦截处理。
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
    public boolean mandatory() {
        return true;
    }

    @Override
    public HookResult beforeToolCall(KernelState state, HookContext context) {
        ToolCallBlock call = context.call();
        if (call == null || !tools.contains(call.name())) {
            return Allow.PLAIN;
        }
        return ask(new Json.Str(call.name()), call.arguments() == null ? new Json.Null() : call.arguments(), 0);
    }

    /** 每个工作做完都会来这里，只看自己的审批工作。失败的结果里没有原来的输入，所以工具、参数和次数由 {@link ApprovalWork} 带回来。 */
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
        return switch (decision) {
            case ApprovalWork.APPROVED -> Allow.PLAIN;
            case ApprovalWork.DENIED -> new Stop("approval denied" + (reply.get("reason") instanceof Json.Str reason ? ": " + reason.value() : ""));
            case ApprovalWork.PENDING -> pending(reply);
            default -> new Stop("approval gave no decision: " + text(work.result()));
        };
    }

    private HookResult pending(Map<String, Json> reply) {
        int poll = reply.get("poll") instanceof Json.Num number ? number.value().intValue() : maxPolls;
        if (poll + 1 >= maxPolls) {
            return new Stop("no approval decision after " + maxPolls + " polls");
        }
        return ask(reply.getOrDefault("tool", new Json.Str("")), reply.getOrDefault("arguments", new Json.Null()), poll + 1);
    }

    /** 键按固定顺序放：输入会写进 journal，回放要逐字节相同。 */
    private static NeedWork ask(Json tool, Json arguments, int poll) {
        Map<String, Json> input = new LinkedHashMap<>();
        input.put("tool", tool);
        input.put("arguments", arguments);
        input.put("poll", new Json.Num(BigDecimal.valueOf(poll)));
        return new NeedWork(ApprovalWork.TYPE, new Json.Obj(input));
    }

    private static String text(Json result) {
        return result instanceof Json.Str value ? value.value() : String.valueOf(result);
    }
}
