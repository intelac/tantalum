package tantalum.value.event;

/**
 * 对会话说的一句话。每种是自己的事件类型（账里说得清是谁、因为什么），但都走同一个处理函数，
 * 怎么办只由内核的 {@code InputPolicy} 那张表决定。改队列、压缩、改名、回答问题、取消、撤下文字、叫停不是输入，是对会话的操作。
 */
public sealed interface Input extends Event permits UserPrompt, Assignment, Report, Notice {

    /** 输入的种类。 */
    enum Kind {
        /** 你说的。 */
        PROMPT,
        /** 另一个会话交代的。 */
        ASSIGNMENT,
        /** 交代出去的事有了结果。 */
        REPORT,
        /** 记一笔：别的会话里发生了你不在场的事（dispatch）。只记进历史，不为它调模型。 */
        NOTICE
    }

    Kind kind();

    /** 追到头是谁引起的。 */
    Root root();
}
