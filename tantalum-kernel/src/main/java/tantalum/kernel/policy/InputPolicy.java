package tantalum.kernel.policy;

import tantalum.value.event.Input;
import tantalum.value.state.SessionKind;

/**
 * 哪种会话收哪种输入、收到怎么办。这类规矩只写在这里：处理输入的函数（{@code InboxSteps}）照这张表办，别处不按会话种类或输入种类判断。
 *
 * <pre>
 *              主会话      普通会话     帮手会话
 * 你说的        收          收          不收
 * 交代          不收        收          收（只收建它的那一条）
 * 结果          收          收          不收
 * 记一笔        收          不收        不收
 * </pre>
 *
 * 收到以后：你说的照你选的办——排队（空闲就开一轮，在忙就排着）或并进正在跑的这一轮；交代开一轮；结果并进正在跑的这一轮，空闲就开一轮；
 * 记一笔只记（dispatch）。普通会话收谁的交代（只收这个人的主会话的）由 {@code InboxSteps} 按交代上记的来处判断。
 */
public final class InputPolicy {

    /** 收到以后怎么办。 */
    public enum Handling {
        /** 空闲就为它开一轮；在忙就排着，这一轮结束后开。 */
        START,
        /** 有一轮在跑就并进去：下次调模型前进历史，而且这一轮要为它再调一次模型；空闲就为它开一轮。 */
        JOIN,
        /** 只记：空闲就进历史；在跑就排着，下次调模型前带上，但不为它多调一次；这一轮结束时还没带上的进历史，不开轮。 */
        RECORD
    }

    /** 表里的一格。不收时 {@code handling} 为 null。 */
    public record Rule(boolean accepted, Handling handling) {}

    private static final Rule REFUSED = new Rule(false, null);
    private static final Rule START = new Rule(true, Handling.START);
    private static final Rule JOIN = new Rule(true, Handling.JOIN);
    private static final Rule RECORD = new Rule(true, Handling.RECORD);

    private InputPolicy() {}

    /** {@code join} 只对“你说的”有意义：你选了并进正在跑的这一轮。 */
    public static Rule of(SessionKind session, Input.Kind input, boolean join) {
        boolean helper = session == SessionKind.HELPER;
        return switch (input) {
            case PROMPT -> helper ? REFUSED : join ? JOIN : START;
            case ASSIGNMENT -> session == SessionKind.MAIN ? REFUSED : START;
            case REPORT -> helper ? REFUSED : JOIN;
            case NOTICE -> session == SessionKind.MAIN ? RECORD : REFUSED;
        };
    }

    /** 这种会话能不能把事交代出去。帮手不能：只有一层。 */
    public static boolean mayAssign(SessionKind session) {
        return session != SessionKind.HELPER;
    }
}
