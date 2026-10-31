package tantalum.shell.model;

/** 接收瞬态分片。分片只用于显示进度，不写 journal，也不进内核。 */
public interface ChunkListener {

    void onText(String text);

    void onReasoning(String text);
}
