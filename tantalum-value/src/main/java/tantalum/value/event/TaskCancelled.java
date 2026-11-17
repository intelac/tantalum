package tantalum.value.event;

/**
 * 放出任务的一方不要它了，进任务自己的会话：它那一轮像被用户撤销一样结束，结果照常交回去（已停止）。
 * 不是人按的停止（那是 {@link Cancel}）：只由 {@code StopTask} 的处理器放进来。
 */
public record TaskCancelled(String task) implements Event {

    public static final String TYPE = "TaskCancelled";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public int version() {
        return 1;
    }
}
