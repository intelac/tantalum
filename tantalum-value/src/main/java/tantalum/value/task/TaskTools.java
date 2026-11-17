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

    /** 任务的编号：放出它的那个事件的序号和它在那次回复里的位置，回放得到同一个。 */
    static String id(long seq, int index) {
        return "t" + seq + "-" + index;
    }

    /** 为一个任务开的会话的编号：放出它的会话的编号接上任务的编号。 */
    static tantalum.value.id.SessionId session(tantalum.value.id.SessionId parent, String task) {
        return new tantalum.value.id.SessionId(parent.value() + "-" + task);
    }

    /**
     * 编号是不是“为任务开的会话”的样子。这样的编号留给任务：人不能用它开会话，否则别人可以先占住一个编号，
     * 让任务送不进去（多用户）。
     */
    static boolean reserved(tantalum.value.id.SessionId session) {
        String value = session.value();
        int dash = value.lastIndexOf("-t");
        if (dash <= 0) {
            return false;
        }
        String[] parts = value.substring(dash + 2).split("-", -1);
        return parts.length == 2 && digits(parts[0]) && digits(parts[1]);
    }

    private static boolean digits(String text) {
        return !text.isEmpty() && text.chars().allMatch(Character::isDigit);
    }
}
