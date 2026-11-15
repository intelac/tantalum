package tantalum.shell.monitor;

/**
 * 一条监视消息：事件此刻走到了哪个阶段。{@code seq} 取号以后才有，{@code command} 只在命令和处理器的阶段有；
 * {@code name} 是事件或命令的类型（工具调用时是工具名），{@code detail} 是一句数值说明；{@code at} 是毫秒时刻；
 * {@code dropped} 是这条之前因为队列满丢了几条。只在内存里，不进 journal。
 */
public record MonitorMessage(Stage stage, String session, String run, Long seq, String command, String name, String detail, long at,
                             int dropped) {

    MonitorMessage withDropped(int count) {
        return new MonitorMessage(stage, session, run, seq, command, name, detail, at, count);
    }
}
