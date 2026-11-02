package tantalum.value.hook;

/** 六个钩子位置。 */
public enum HookPoint {
    BEFORE_MODEL_CALL,
    BEFORE_TOOL_CALL,
    ON_FAILURE,
    BEFORE_RUN_END,
    ON_WORK_RESULT,
    BEFORE_COMPACT
}
