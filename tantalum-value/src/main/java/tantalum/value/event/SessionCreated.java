package tantalum.value.event;

import tantalum.value.id.SessionId;

import java.util.Objects;
import tantalum.value.state.HelperOf;
import tantalum.value.state.SessionKind;

/**
 * 建一个会话，是它在账里的第一个事件。{@code dir} 是工作目录（用户电脑上的绝对路径），可以没有；{@code offRecord} 为真是“不进记忆”的会话；
 * 这两样和种类一样建好不变。{@code helper} 只有帮手会话有。会话已经存在时再收到它，什么都不改。
 */
public record SessionCreated(SessionKind kind, String dir, boolean offRecord, HelperOf helper, SessionId issuer) implements Event {

    public static final String TYPE = "SessionCreated";

    public SessionCreated {
        Objects.requireNonNull(kind, "kind");
        if ((kind == SessionKind.HELPER) != (helper != null)) {
            throw new IllegalArgumentException("exactly a helper session says whose helper it is");
        }
        if (issuer != null && kind != SessionKind.REGULAR) {
            throw new IllegalArgumentException("only a regular session is opened by a main session (dispatch)");
        }
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public int version() {
        return 1;
    }
}
