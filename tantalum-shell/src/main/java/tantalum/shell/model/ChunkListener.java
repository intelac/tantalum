package tantalum.shell.model;

/** 接收瞬态分片。分片只用于显示进度，不写 journal，也不进内核。 */
public interface ChunkListener {

    void onText(String text);

    void onReasoning(String text);

    /** 工具调用参数的一个分片，块开始时传空串。只用来量首 token：只回工具调用的回复也有首 token。默认忽略。 */
    default void onToolInput(String fragment) {
    }
}
