package tantalum.value.assignment;

/** 交代出去的一件事怎么结束的。每次交代恰好有一个。{@code said} 是告诉模型时的说法。 */
public enum AssignmentOutcome {
    COMPLETED("completed"),
    FAILED("failed"),
    /** 被模型或用户停掉。 */
    STOPPED("stopped before it finished"),
    /** 期限到了还没结束。 */
    TIMED_OUT("timed out and was stopped"),
    /** 服务器重启打断，不重新执行。 */
    INTERRUPTED("interrupted by a server restart; it was not run again");

    private final String said;

    AssignmentOutcome(String said) {
        this.said = said;
    }

    public String said() {
        return said;
    }
}
