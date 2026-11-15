package tantalum.shell.monitor;

/**
 * 流水线上的阶段（live-monitor 需求 1.1）：监视插件和平台之间的约定。组件以后改名、重构，只要阶段不变，插件就不用跟着改。
 */
public enum Stage {
    /** 提交被入口接受。 */
    INTAKE,
    /** 进了输入环，含处理器的结果回来。 */
    INPUT,
    /** 用户通道给它取了序号。 */
    LANE,
    /** 净室里内核走完一步（内核本身不知道有监视）。 */
    KERNEL,
    /** 这一行落盘。 */
    JOURNAL,
    /** 推给页面、写索引。 */
    PUSH,
    /** 命令进了输出环。 */
    OUTPUT,
    HANDLER_START,
    HANDLER_END,
    /** 发给用户电脑上的连接程序。 */
    REMOTE_SEND,
    REMOTE_DONE
}
