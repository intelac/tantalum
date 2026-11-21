package tantalum.value.state;

/**
 * 会话的种类，建会话时定下，之后不变。每种会话收哪些输入由内核的 {@code InputPolicy} 定，
 * 有哪些工具、你能对它做什么由外壳的 {@code SessionProfile} 定；别处不按种类判断。
 */
public enum SessionKind {
    /** 主会话：你平时说话的地方。 */
    MAIN,
    /** 普通会话：干活的地方。 */
    REGULAR,
    /** 帮手会话：为一次交代专门开的，做完那一件事。 */
    HELPER
}
