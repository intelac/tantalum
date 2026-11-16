package tantalum.value.state;

import java.util.ArrayList;
import java.util.List;
import tantalum.value.event.SessionMessage;

/** 这个会话正忙时别的会话送来、要回话、还没开始处理的话，按到的顺序。用户排队的提问优先，没有了再轮到它们。 */
public record Inbox(List<SessionMessage> waiting) {

    public static final Inbox EMPTY = new Inbox(List.of());

    public Inbox {
        waiting = waiting == null ? List.of() : List.copyOf(waiting);
    }

    public Inbox add(SessionMessage message) {
        List<SessionMessage> grown = new ArrayList<>(waiting);
        grown.add(message);
        return new Inbox(grown);
    }

    /** 去掉排在最前面的那一句。 */
    public Inbox rest() {
        return new Inbox(waiting.subList(1, waiting.size()));
    }

    public boolean isEmpty() {
        return waiting.isEmpty();
    }
}
