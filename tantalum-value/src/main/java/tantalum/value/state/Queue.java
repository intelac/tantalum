package tantalum.value.state;

import java.util.List;
import tantalum.value.model.Message;

/** 在排队的提问里找一条。排队的提问没有编号，靠位置加内容定位。只有静态方法；value 层只放记录、接口和枚举，所以写成接口。 */
public interface Queue {

    /**
     * 从 {@code index}（超出就从末尾）往前找第一条等于 {@code expected} 的，返回它的位置；没有返回 -1。
     * 只往前找：队列只会从头上取走或中间删掉，一条消息的位置只会变小。
     */
    static int locate(List<Message> followUps, int index, Message expected) {
        for (int i = Math.min(index, followUps.size() - 1); i >= 0; i--) {
            if (followUps.get(i).equals(expected)) {
                return i;
            }
        }
        return -1;
    }
}
