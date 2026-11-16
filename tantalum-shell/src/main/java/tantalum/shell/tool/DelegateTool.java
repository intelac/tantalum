package tantalum.shell.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.ToolDispatch;
import tantalum.shell.plugin.ToolOutput;
import tantalum.value.delegate.DelegateCall;
import tantalum.value.json.Json;

/**
 * {@code delegate} 给模型看的定义（kernel-delegation）。调用由内核处理，不在这里执行：内核发“交活”命令，子会话的账里多一条交活事件；
 * 子会话一轮结束时由它的内核发“交回”，结果作为事件回到这里的账里。等待是内核里的一个状态（{@code AwaitingDelegate}），没有线程在等。
 * 做法照 {@link AskUserTool}。
 */
public final class DelegateTool implements Tool {

    public static final String NAME = DelegateCall.NAME;

    /** 由内核处理，不真的执行。 */
    static final ToolDispatch DISPATCH = ToolDispatch.parallel(NAME, "delegate", 8);

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "Hand a self-contained piece of work to a sub-conversation of the same user. task: what to do; brief: what it needs "
                + "to know (it does not see this conversation). wait: true (default) waits for its result, up to 10 minutes; false "
                + "returns at once and its result is reported back here later, which is the way for long work. session: give the id "
                + "of a sub-conversation you started earlier to hand it more work with its history kept. Several delegate calls in "
                + "one reply run side by side. The sub-conversation can read memory but only add notes; at most two levels deep.";
    }

    /** 手写 schema：{@code wait} 是布尔值。 */
    @Override public Json schema() {
        Map<String, Json> properties = new LinkedHashMap<>();
        properties.put("task", property("string", "What the sub-conversation should do."));
        properties.put("brief", property("string", "Background it needs: it cannot see this conversation."));
        properties.put("wait", property("boolean", "Wait for the result (default true). Use false for long work."));
        properties.put("session", property("string", "Id of a sub-conversation started earlier, to hand it more work."));
        Map<String, Json> schema = new LinkedHashMap<>();
        schema.put("type", new Json.Str("object"));
        schema.put("properties", new Json.Obj(properties));
        schema.put("required", new Json.Arr(List.of(new Json.Str("task"))));
        schema.put("additionalProperties", new Json.Bool(false));
        return new Json.Obj(schema);
    }

    private static Json property(String type, String description) {
        Map<String, Json> property = new LinkedHashMap<>();
        property.put("type", new Json.Str(type));
        property.put("description", new Json.Str(description));
        return new Json.Obj(property);
    }

    @Override public String server() { return DISPATCH.server(); }
    @Override public ToolDispatch dispatch() { return DISPATCH; }
    @Override public boolean isConcurrencySafe(Json arguments) { return true; }

    @Override
    public ToolOutput run(ToolContext context, Json arguments) {
        throw new IllegalStateException("delegate is handled by the kernel, not by the tool handler");
    }
}
