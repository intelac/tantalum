package tantalum.value.model;

/** 规范化后的模型失败原因。 */
public enum FailureReason {
    CONTEXT_OVERFLOW,
    REFUSAL,
    RATE_LIMITED,
    TIMEOUT,
    SERVER_ERROR,
    CONNECTION_ERROR,
    INVALID_REQUEST,
    UNKNOWN
}
