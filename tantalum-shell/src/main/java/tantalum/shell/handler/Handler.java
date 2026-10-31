package tantalum.shell.handler;

import tantalum.value.command.Command;
import tantalum.value.event.Event;

/** 执行一类命令。运行在自己的虚拟线程上，全部 I/O 都在这里。 */
public interface Handler {

    /** Handler 的主体，也方便测试写假的 Handler。 */
    @FunctionalInterface
    interface Body {
        Event run(Command command) throws Exception;
    }

    /** 返回结果事件。返回 null 表示不产生结果，只有命令已被中止时才允许。 */
    Event handle(Command command) throws Exception;

    /** {@link #handle} 抛异常时，转成这条命令的失败事件。 */
    Event failed(Command command, Exception failure);
}
