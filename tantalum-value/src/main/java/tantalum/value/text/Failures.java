package tantalum.value.text;

/**
 * 异常怎么写进事实、事件和工具结果：只给类名，不带消息。异常消息可能带路径、地址、令牌，
 * 而事实和事件进 journal、推送和模型上下文，这三处都不能有它们；原文由能记日志的那一层记进服务器日志。
 */
public interface Failures {

    /** 异常类的简单名，如 {@code IllegalStateException}。 */
    static String describe(Throwable failure) {
        return failure.getClass().getSimpleName();
    }

    /** {@code what: 类名}，如 {@code cannot load session: NoSuchFileException}。 */
    static String describe(Throwable failure, String what) {
        return what + ": " + describe(failure);
    }
}
