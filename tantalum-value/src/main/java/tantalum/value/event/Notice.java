package tantalum.value.event;

import java.util.Objects;
import tantalum.value.id.SessionId;

/**
 * 记一笔（dispatch 需求 3）：主会话开的普通会话里，你直接说的那一轮结束了，进主会话：哪个会话、你说了什么、它答了什么。
 * 只记进历史，不为它调模型；不是你对主会话说的话。只由送达（{@code Deliver}）放进来。{@code id} 是这次送达的编号的一部分。
 */
public record Notice(String id, SessionId from, String text) implements Input {

    public static final String TYPE = "Notice";

    public Notice {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(text, "text");
    }

    @Override
    public Kind kind() {
        return Kind.NOTICE;
    }

    /** 你在那个会话里说的话引起的。 */
    @Override
    public Root root() {
        return Root.USER;
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
