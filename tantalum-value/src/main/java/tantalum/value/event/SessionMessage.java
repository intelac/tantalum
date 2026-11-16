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
 * <p>
 * 第三种话是撤回（{@code withdraw} 为真，平时为空）：{@code from} 那个会话说“我交给你的活不要了”。收到的会话把那个会话交来的、
 * 还在做或还在排队的活停下，每一件照常回一句“没做成”，所以每件交出去的活仍然恰好有一句回话；已经做完的不受影响。
 * 撤回本身没有回话。只有交活的那个会话能撤回自己交的。
 */
public record SessionMessage(CommandId id, RunRef from, String text, CommandId answers, boolean failed, int hops,
                             ModelChoice model, String dir, Boolean offRecord, Boolean withdraw) implements Event {

    public static final String TYPE = "SessionMessage";

    public SessionMessage {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(from, "from");
        text = text == null ? "" : text;
    }

    public SessionMessage(CommandId id, RunRef from, String text, CommandId answers, boolean failed, int hops,
                          ModelChoice model, String dir, Boolean offRecord) {
        this(id, from, text, answers, failed, hops, model, dir, offRecord, null);
    }

    /** 一句撤回：{@code from} 所在的会话收回它交出去的活。 */
    public static SessionMessage withdrawal(CommandId id, RunRef from) {
        return new SessionMessage(id, from, "", null, false, 0, null, null, null, Boolean.TRUE);
    }

    /** 这是一句撤回。 */
    public boolean isWithdrawal() {
        return Boolean.TRUE.equals(withdraw);
    }

    /** 这是交来的活（不是回话，也不是撤回）。 */
    public boolean isRequest() {
        return answers == null && !isWithdrawal();
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
