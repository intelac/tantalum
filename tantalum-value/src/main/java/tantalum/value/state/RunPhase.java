package tantalum.value.state;

/** 内核固定的三个状态。 */
public sealed interface RunPhase permits Deciding, Awaiting, Ended {

    /** 转移表用的状态名：等待时是 {@code resumeAt}，其余是固定状态的名字。 */
    String name();
}
