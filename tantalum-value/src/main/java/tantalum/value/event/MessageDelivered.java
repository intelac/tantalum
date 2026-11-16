package tantalum.value.event;

import tantalum.value.id.CommandId;

/**
 * 确认：这个会话送出去的那句话（{@code message} 是它的编号）已经进了对方会话的账。收的一方每收到一句（包括重送来的）都回一个；
 * 送的一方据此把它从“还没确认”里去掉。确认本身不用再确认：它丢了，重启后那句话会重送，对方去重后再回一次。
 */
public record MessageDelivered(CommandId message) implements Event {

    public static final String TYPE = "MessageDelivered";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public int version() {
        return 1;
    }
}
