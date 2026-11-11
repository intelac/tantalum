package tantalum.value.state;

import java.util.List;
import tantalum.value.model.Message;
import tantalum.value.model.SkillBlock;

/** 在排队的提问里找一条。排队的提问没有编号，靠位置加内容定位。只有静态方法；value 层只放记录、接口和枚举，所以写成接口。 */
public interface Queue {

    /**
     * 从 {@code index}（超出就从末尾）往前找第一条等于 {@code expected} 的，返回它的位置；没有返回 -1。
     * 只往前找：队列只会从头上取走或中间删掉，一条消息的位置只会变小。
     */
    static int locate(List<Message> followUps, int index, Message expected) {
        for (int i = Math.min(index, followUps.size() - 1); i >= 0; i--) {
            if (plain(followUps.get(i)).equals(expected)) {
                return i;
            }
        }
        return -1;
    }

    /** 定位只比文字和附件：点名的技能内容是服务器附上的，改队列的请求里没有它。 */
    private static Message plain(Message message) {
        if (message.content().stream().noneMatch(SkillBlock.class::isInstance)) {
            return message;
        }
        return new Message(message.role(), message.content().stream().filter(block -> !(block instanceof SkillBlock)).toList(),
                message.interrupted());
    }
}
