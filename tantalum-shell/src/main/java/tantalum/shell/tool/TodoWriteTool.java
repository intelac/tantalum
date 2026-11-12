package tantalum.shell.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.ToolDispatch;
import tantalum.shell.plugin.ToolOutput;
import tantalum.value.json.Json;
import tantalum.value.todo.TodoList;

/**
 * 内置工具 {@code todo_write}（todo-tool，照 DSH {@code tool-todo}）：模型做多步的活时写下待办清单，每次给整张，替换上一张。
 * 清单不另外存：当前清单就是会话里最后一次成功调用的参数，回放、重启、换电脑都一样。在服务器上执行，不碰用户的电脑。
 */
public final class TodoWriteTool implements Tool {

    /** 说明照 DSH 单一活跃那一版（{@code allowParallelInProgress: false}）。 */
    static final String DESCRIPTION = "Record and update a structured task list for the current work. Send the ENTIRE list every call: "
            + "it REPLACES the previous list (there are no partial updates, no per-item edits). Use it to plan multi-step work and "
            + "show progress: add one todo per concrete step before you start. Keep exactly one todo in_progress while work remains. "
            + "Mark a todo completed the moment it is done (do not batch completions). Skip the list for trivial single-step tasks. "
            + "Statuses: pending (not started), in_progress (being worked on now), completed (finished).";

    /** 同一回复里写两次清单要按顺序来，所以独占；只是记一张表，不限并发数之外的东西。 */
    static final ToolDispatch DISPATCH = ToolDispatch.exclusive(TodoList.TOOL, "todos", 16);

    @Override public String name() { return TodoList.TOOL; }

    @Override public String description() { return DESCRIPTION; }

    @Override
    public Json schema() {
        Map<String, Json> status = new LinkedHashMap<>();
        status.put("type", new Json.Str("string"));
        status.put("enum", new Json.Arr(List.of(new Json.Str("pending"), new Json.Str("in_progress"), new Json.Str("completed"))));
        status.put("description", new Json.Str("pending (not started) | in_progress (now) | completed (done)."));
        Map<String, Json> content = new LinkedHashMap<>();
        content.put("type", new Json.Str("string"));
        content.put("description", new Json.Str("What the task is: a short imperative line."));
        Map<String, Json> itemProperties = new LinkedHashMap<>();
        itemProperties.put("content", new Json.Obj(content));
        itemProperties.put("status", new Json.Obj(status));
        Map<String, Json> item = new LinkedHashMap<>();
        item.put("type", new Json.Str("object"));
        item.put("properties", new Json.Obj(itemProperties));
        item.put("required", new Json.Arr(List.of(new Json.Str("content"), new Json.Str("status"))));
        item.put("additionalProperties", new Json.Bool(false));
        Map<String, Json> todos = new LinkedHashMap<>();
        todos.put("type", new Json.Str("array"));
        todos.put("items", new Json.Obj(item));
        todos.put("description", new Json.Str("The COMPLETE task list, replacing any previous list."));
        Map<String, Json> schema = new LinkedHashMap<>();
        schema.put("type", new Json.Str("object"));
        schema.put("properties", new Json.Obj(Map.of("todos", new Json.Obj(todos))));
        schema.put("required", new Json.Arr(List.of(new Json.Str("todos"))));
        schema.put("additionalProperties", new Json.Bool(false));
        return new Json.Obj(schema);
    }

    @Override public String server() { return DISPATCH.server(); }

    @Override public ToolDispatch dispatch() { return DISPATCH; }

    /** 合格就回一行进度；不合格回错误，说明哪一条不对，模型自己改，清单不变（最后一次成功的仍是当前清单）。 */
    @Override
    public ToolOutput run(ToolContext context, Json arguments) {
        try {
            return ToolOutput.ok(TodoList.parse(arguments).summary());
        } catch (IllegalArgumentException invalid) {
            return ToolOutput.error(invalid.getMessage());
        }
    }
}
