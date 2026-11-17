package tantalum.value.task;

/** 内核自己处理的两个工具的名字和参数名：不发 {@code RunTool}，当场有结果。 */
public interface TaskTools {

    String TASK = "task";
    String TASK_STOP = "task_stop";

    /** {@code task} 的参数名。 */
    String PROMPT = "prompt";
    String AGENT = "agent";
    /** {@code task_stop} 的参数名。 */
    String ID = "id";

    static boolean handles(String tool) {
        return TASK.equals(tool) || TASK_STOP.equals(tool);
    }
}
