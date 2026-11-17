package tantalum.value.task;

/** 内核自己处理的两个工具的名字：不发 {@code RunTool}，当场有结果。 */
public final class TaskTools {

    public static final String TASK = "task";
    public static final String TASK_STOP = "task_stop";

    /** {@code task} 的参数名。 */
    public static final String PROMPT = "prompt";
    public static final String AGENT = "agent";
    /** {@code task_stop} 的参数名。 */
    public static final String ID = "id";

    private TaskTools() {}

    public static boolean handles(String tool) {
        return TASK.equals(tool) || TASK_STOP.equals(tool);
    }
}
