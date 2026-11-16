package tantalum.value.state;

import java.util.ArrayList;
import java.util.List;
import tantalum.value.event.SessionMessage;
import tantalum.value.id.CommandId;
import tantalum.value.id.SessionId;

/**
 * 一个会话的信箱，三样东西：
 * <ul>
 * <li>{@code waiting}：这个会话正忙时别的会话送来、还没开始处理的话，按到的顺序。用户排队的提问优先，没有了再轮到它们。</li>
 * <li>{@code unconfirmed}：这个会话送出去、对方还没确认收到的话。对方收到后回一个确认（{@code MessageDelivered}）才去掉；
 * 服务器重启后还在这里的重新送一次（{@code Redeliver}）。所以一句话不会因为进程在“记了账、还没送到”之间死掉而丢。</li>
 * <li>{@code seen}：最近收到过的话的编号。重送的话编号相同，收的一方据此只处理一次、照样回确认。只留最近 {@link #SEEN} 个。</li>
 * </ul>
 */
public record Inbox(List<SessionMessage> waiting, List<Outgoing> unconfirmed, List<CommandId> seen) {

    public static final Inbox EMPTY = new Inbox(List.of(), List.of(), List.of());
    /** 记住最近收到的多少个编号：重送只发生在重启后，离原来那次不远。 */
    public static final int SEEN = 64;

    /** 送出去的一句话和它的去处。 */
    public record Outgoing(SessionId to, SessionMessage message) {}

    public Inbox {
        waiting = waiting == null ? List.of() : List.copyOf(waiting);
        unconfirmed = unconfirmed == null ? List.of() : List.copyOf(unconfirmed);
        seen = seen == null ? List.of() : List.copyOf(seen);
    }

    public Inbox(List<SessionMessage> waiting) {
        this(waiting, List.of(), List.of());
    }

    public Inbox add(SessionMessage message) {
        List<SessionMessage> grown = new ArrayList<>(waiting);
        grown.add(message);
        return new Inbox(grown, unconfirmed, seen);
    }

    /** 去掉排在最前面的那一句。 */
    public Inbox rest() {
        return new Inbox(waiting.subList(1, waiting.size()), unconfirmed, seen);
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
        return new Inbox(waiting, grown, seen);
    }

    /** 对方确认收到了这一句。没有这一句时原样返回。 */
    public Inbox confirmed(CommandId message) {
        if (unconfirmed.stream().noneMatch(each -> each.message().id().equals(message))) {
            return this;
        }
        return new Inbox(waiting, unconfirmed.stream().filter(each -> !each.message().id().equals(message)).toList(), seen);
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
        return new Inbox(waiting, unconfirmed, grown.size() > SEEN ? grown.subList(grown.size() - SEEN, grown.size()) : grown);
    }
}
