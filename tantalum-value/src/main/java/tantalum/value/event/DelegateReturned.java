package tantalum.value.event;

import java.util.Objects;
import tantalum.value.id.CommandId;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;

/**
 * 交回（kernel-delegation）：子会话把一次交活的结局送回来，进父会话的账。{@code delegation} 是委派号。种类：结果（文字是子运行最后的
 * 回答）、问题（子运行调了 {@code ask_user}，{@code question} 是问题 id，文字是问题）、失败、被取消、被重启打断、被钩子停下、
 * 没开成（子会话收不了这次交活）。父运行正在等这个委派号时，文字写成那次工具调用的结果；没人在等时是一条汇报。
 * 同一个委派号的交回可能来两次（重启后重发），第二次被忽略。
 */
public record DelegateReturned(CommandId delegation, SessionId child, RunId childRun, Kind kind, String text, String question)
        implements Event {

    public static final String TYPE = "DelegateReturned";

    public enum Kind { RESULT, QUESTION, FAILED, CANCELLED, INTERRUPTED, STOPPED, REFUSED }

    public DelegateReturned {
        Objects.requireNonNull(delegation, "delegation");
        Objects.requireNonNull(child, "child");
        Objects.requireNonNull(kind, "kind");
        text = text == null ? "" : text;
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
