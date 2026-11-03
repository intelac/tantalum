package tantalum.kernel;

/** 内核的两个上限：一次最多并行几个工具；一个等待点连续最多几轮插件工作。 */
public record Limits(int maxParallelTools, int maxWorkRounds) {

    /** 和 application.properties 的默认值一致。 */
    public static final Limits DEFAULT = new Limits(10, 8);
}
