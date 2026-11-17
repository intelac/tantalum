package tantalum.value.task;

/** 一个任务怎么结束的。每个任务恰好有一个。 */
public enum TaskOutcome {
    COMPLETED,
    FAILED,
    /** 被模型或用户停掉。 */
    STOPPED,
    /** 期限到了还没结束。 */
    TIMED_OUT,
    /** 服务器重启打断，不重新执行。 */
    INTERRUPTED
}
