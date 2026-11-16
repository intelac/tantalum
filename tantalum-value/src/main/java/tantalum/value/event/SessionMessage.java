package tantalum.value.event;

import java.util.Objects;
import tantalum.value.id.CommandId;
import tantalum.value.id.RunRef;
import tantalum.value.model.ModelChoice;

/**
 * 会话来话：另一个会话对这个会话说了一句话，进这个会话的账。它是处理器送来的（机器这边的门），不是 {@link UserPrompt}：
 * 人说的话只从入口进来，模型写的话不管送到哪个会话都不算人说的。
 * <p>
 * 一来一回用的是同一种事件。{@code answers} 为 null 是一句要回话的话（交来的活）：这个会话为它跑一轮，那一轮结束时把结果
 * 作为另一条会话来话送回去，那一条的 {@code answers} 就是这一条的 {@code id}。{@code answers} 不为 null 是回话：
 * 对方正在等这个编号就写成那次工具调用的结果，没在等就当一条消息处理。
 * <p>
 * {@code id} 是这句话的编号（发它的那条 {@code Send} 命令的编号）。{@code from} 是说这句话的那一轮运行。
 * {@code failed} 为真表示回话说的是“没做成”（失败、被取消、被重启打断、不接）。{@code hops} 是这句话转了几手：
 * 用户发起的一轮送出的是 1，为回它而开的一轮再送出的是 2，超过上限不接。
 * {@code model}、{@code dir}、{@code offRecord} 只在这个会话还没有任何消息时定下。
 */
public record SessionMessage(CommandId id, RunRef from, String text, CommandId answers, boolean failed, int hops,
                             ModelChoice model, String dir, Boolean offRecord) implements Event {

    public static final String TYPE = "SessionMessage";

    public SessionMessage {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(from, "from");
        text = text == null ? "" : text;
    }

    /** 这是一句回话（不是交来的活）。 */
    public boolean isReply() {
        return answers != null;
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
