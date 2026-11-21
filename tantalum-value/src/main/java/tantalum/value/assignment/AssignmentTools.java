package tantalum.value.assignment;

/** 内核自己处理的两个工具的名字和参数名：不发 {@code RunTool}，当场有结果。给模型的名字还是“任务”。 */
public interface AssignmentTools {
    String TASK = "task";
    String TASK_STOP = "task_stop";
    /** {@code task} 的参数名。 */
    String PROMPT = "prompt";
    String AGENT = "agent";
    /** 主会话的 task：交给哪个已有的普通会话（追加、重试），不给就开一个新的（dispatch）。 */
    String SESSION = "session";
    /** 主会话的 task：新开的会话在哪个工作区（填工作区的名字），不给就没有工作目录。 */
    String WORKSPACE = "workspace";
    /** {@code task_stop} 的参数名。 */
    String ID = "id";

    static boolean handles(String tool) {
        return TASK.equals(tool) || TASK_STOP.equals(tool);
    }

    /** 交代的编号：放出它的那个事件的序号和它在那次回复里的位置，回放得到同一个。 */
    static String id(long seq, int index) {
        return "t" + seq + "-" + index;
    }
}
