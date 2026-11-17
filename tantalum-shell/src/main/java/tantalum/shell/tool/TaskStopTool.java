package tantalum.shell.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.ToolDispatch;
import tantalum.shell.plugin.ToolOutput;
import tantalum.value.json.Json;
import tantalum.value.task.TaskTools;

/** 内置工具 {@code task_stop}：只有给模型的定义，调用由内核截下。停的是还没回来的任务，它的结果照常回来（已停止）。 */
public final class TaskStopTool implements Tool {

    @Override public String name() { return TaskTools.TASK_STOP; }

    @Override public String description() {
        return "Stop a task you started that has not come back yet, when its work is no longer needed. Its result still arrives, "
                + "marked as stopped.";
    }

    @Override public Json schema() {
        Map<String, Json> properties = new LinkedHashMap<>();
        properties.put(TaskTools.ID, TaskTool.property("The id the task call returned."));
        Map<String, Json> schema = new LinkedHashMap<>();
        schema.put("type", new Json.Str("object"));
        schema.put("properties", new Json.Obj(properties));
        schema.put("required", new Json.Arr(List.of(new Json.Str(TaskTools.ID))));
        schema.put("additionalProperties", new Json.Bool(false));
        return new Json.Obj(schema);
    }

    static final ToolDispatch DISPATCH = ToolDispatch.parallel(TaskTools.TASK_STOP, "tasks", 1);

    @Override public String server() { return DISPATCH.server(); }

    @Override public ToolDispatch dispatch() { return DISPATCH; }

    /** 只有一层：任务不能再放任务。内核也拦着。 */
    @Override public boolean inTasks() { return false; }

    @Override
    public ToolOutput run(ToolContext context, Json arguments) {
        throw new IllegalStateException("task_stop is handled by the kernel, not by the tool handler");
    }
}
