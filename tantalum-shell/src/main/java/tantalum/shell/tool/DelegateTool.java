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
 * {@code delegate} 给模型看的定义：让另一个会话做一件事。调用由内核处理，不在这里执行：内核发“送话”命令，那个会话的账里多一条
 * 会话来话；它为这句话跑的一轮结束时，结果作为另一条会话来话回来。不等的当场完成；要等的停在内核的“等会话回答”，没有线程在等。
 * 做法照 {@link AskUserTool}。
 */
public final class DelegateTool implements Tool {

    public static final String NAME = DelegateCall.NAME;

    /** 由内核处理，不真的执行。 */
    static final ToolDispatch DISPATCH = ToolDispatch.parallel(NAME, "delegate", 8);

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "Hand a piece of work to another conversation of this user. task: what to do; brief: what it needs to know (it cannot "
                + "see this conversation). session: the id of an existing conversation to hand it to; leave it out to open a new one. "
                + "workspace: for a new conversation, the path of the workspace it should work in (see the workspaces section); by "
                + "default it works in the same folder as this one. "
                + "wait: false (default) returns at once, you go on, and its answer comes back later as a message, which is the way "
                + "for long work; true waits for its answer in this turn (up to 10 minutes) and gives it as the result. Several "
                + "calls in one reply run side by side. The other conversation is an ordinary one the user can open and talk to.";
    }

    /** 手写 schema：{@code wait} 是布尔值。 */
    @Override public Json schema() {
        Map<String, Json> properties = new LinkedHashMap<>();
        properties.put("task", property("string", "What the other conversation should do."));
        properties.put("brief", property("string", "Background it needs: it cannot see this conversation."));
        properties.put("session", property("string", "Id of an existing conversation to hand the work to. Omit to open a new one."));
        properties.put("workspace", property("string", "Path of the workspace a new conversation works in. Not together with session."));
        properties.put("wait", property("boolean", "Wait for the answer in this turn (default false)."));
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
