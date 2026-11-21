package tantalum.value.event;

import tantalum.value.id.SessionId;

/**
 * 交代的一方不要它了，进接了这件事的会话：为它开的那一轮像被用户撤销一样结束，结果照常交回去（已停止）。
 * 不是人按的停止（那是 {@link Cancel}）：只由送达（{@code Deliver}）放进来，排在那次交代后面；{@code from} 是交代的会话。
 */
public record AssignmentCancelled(String assignment, SessionId from) implements Event {

    public static final String TYPE = "AssignmentCancelled";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public int version() {
        return 1;
    }
}
