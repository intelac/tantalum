package tantalum.value.state;

/** 内核固定的三个状态。 */
public sealed interface RunPhase permits Deciding, Awaiting, Ended {}
