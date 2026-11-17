package tantalum.value.state;

import java.util.ArrayList;
import java.util.List;
import tantalum.value.event.SessionMessage;
import tantalum.value.id.CommandId;
import tantalum.value.id.SessionId;

/**
 * 一个会话的信箱，四样东西：
 * <ul>
 * <li>{@code waiting}：这个会话正忙时别的会话送来、还没开始处理的话，按到的顺序。用户排队的提问优先，没有了再轮到它们。</li>
 * <li>{@code unconfirmed}：这个会话送出去、对方还没确认收到的话。对方收到后回一个确认（{@code MessageDelivered}）才去掉；
 * 没确认的隔一阵重新送一次（{@code Redeliver}），每送一次记一次数，到了上限就放弃并告诉这个会话。所以一句话不会因为进程在
 * “记了账、还没送到”之间死掉而丢，也不会永远没有结局。</li>
 * <li>{@code seen}：最近收到过的话的编号。重送的话编号相同，收的一方据此只处理一次、照样回确认。只留最近 {@link #SEEN} 个。</li>
 * <li>{@code told}：{@code waiting} 里已经告诉过对方“这边在等用户”的那几句的编号，一句只说一次；那一句开始处理就去掉。</li>
 * </ul>
 */
public record Inbox(List<SessionMessage> waiting, List<Outgoing> unconfirmed, List<CommandId> seen, List<CommandId> told) {

    public static final Inbox EMPTY = new Inbox(List.of(), List.of(), List.of(), List.of());
    /**
     * 记住最近收到的多少个编号。重送有次数上限（内核里的 {@code MAX_REDELIVER}），一句话从第一次送到最后一次重送之间隔不了多久；
     * 要让重送的被当成新的，得在这段时间里另外收到这么多句，所以取得比一个会话短时间内收得到的多得多。
     */
    public static final int SEEN = 512;

    /** 送出去的一句话、它的去处、重送过几次。 */
    public record Outgoing(SessionId to, SessionMessage message, int attempts) {
        public Outgoing(SessionId to, SessionMessage message) {
            this(to, message, 0);
        }
    }

    public Inbox {
        waiting = waiting == null ? List.of() : List.copyOf(waiting);
        unconfirmed = unconfirmed == null ? List.of() : List.copyOf(unconfirmed);
        seen = seen == null ? List.of() : List.copyOf(seen);
        told = told == null ? List.of() : List.copyOf(told);
    }

    public Inbox(List<SessionMessage> waiting, List<Outgoing> unconfirmed, List<CommandId> seen) {
        this(waiting, unconfirmed, seen, List.of());
    }

    public Inbox(List<SessionMessage> waiting) {
        this(waiting, List.of(), List.of(), List.of());
    }

    public Inbox add(SessionMessage message) {
        List<SessionMessage> grown = new ArrayList<>(waiting);
        grown.add(message);
        return new Inbox(grown, unconfirmed, seen, told);
    }

    /** 去掉排在最前面的那一句。 */
    public Inbox rest() {
        return withWaiting(waiting.subList(1, waiting.size()));
    }

    /** 换掉等着处理的那些；不在里面的编号从“说过在等用户”里去掉。 */
    public Inbox withWaiting(List<SessionMessage> kept) {
        List<CommandId> ids = kept.stream().map(SessionMessage::id).toList();
        return new Inbox(kept, unconfirmed, seen, told.stream().filter(ids::contains).toList());
    }

    /** 这一句已经告诉过对方“这边在等用户”。 */
    public boolean wasTold(CommandId message) {
        return told.contains(message);
    }

    public Inbox told(CommandId message) {
        if (told.contains(message)) {
            return this;
        }
        List<CommandId> grown = new ArrayList<>(told);
        grown.add(message);
        return new Inbox(waiting, unconfirmed, seen, grown);
    }

    /** 没有等着处理的来话（送出去没确认的不算）。 */
    public boolean isEmpty() {
        return waiting.isEmpty();
    }

    /** 送出了一句：记着，等对方确认。同一句（编号相同）只记一次。 */
    public Inbox sent(SessionId to, SessionMessage message) {
        if (unconfirmed.stream().anyMatch(each -> each.message().id().equals(message.id()))) {
            return this;
        }
        List<Outgoing> grown = new ArrayList<>(unconfirmed);
        grown.add(new Outgoing(to, message));
        return new Inbox(waiting, grown, seen, told);
    }

    /** 这一句又送了一次：次数加一。 */
    public Inbox retried(CommandId message) {
        return new Inbox(waiting, unconfirmed.stream().map(each -> each.message().id().equals(message)
                ? new Outgoing(each.to(), each.message(), each.attempts() + 1) : each).toList(), seen, told);
    }

    /** 对方确认收到了这一句（或者不再送了）。没有这一句时原样返回。 */
    public Inbox confirmed(CommandId message) {
        if (unconfirmed.stream().noneMatch(each -> each.message().id().equals(message))) {
            return this;
        }
        return new Inbox(waiting, unconfirmed.stream().filter(each -> !each.message().id().equals(message)).toList(), seen, told);
    }

    public boolean hasSeen(CommandId message) {
        return seen.contains(message);
    }

    /** 收到了这一句：记下编号。 */
    public Inbox saw(CommandId message) {
        if (seen.contains(message)) {
            return this;
        }
        List<CommandId> grown = new ArrayList<>(seen);
        grown.add(message);
        return new Inbox(waiting, unconfirmed, grown.size() > SEEN ? grown.subList(grown.size() - SEEN, grown.size()) : grown, told);
    }
}
