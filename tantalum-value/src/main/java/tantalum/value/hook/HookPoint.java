package tantalum.value.hook;

/** 七个钩子位置。 */
public enum HookPoint {
    BEFORE_MODEL_CALL,
    BEFORE_TOOL_CALL,
    ON_FAILURE,
    BEFORE_RUN_END,
    ON_WORK_RESULT,
    BEFORE_COMPACT,
    /** 用户回答了钩子问的问题，或期限到了（waiting-state）。 */
    ON_ANSWER
}
