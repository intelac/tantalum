package tantalum.value.state;

/** 内核固定的四个状态：决定中、等命令结果、等用户回答（waiting-state）、已结束。 */
public sealed interface RunPhase permits Deciding, Awaiting, AwaitingAnswer, Ended {

    /** 转移表用的状态名：等待时是 {@code resumeAt}，其余是固定状态的名字。 */
    String name();
}
