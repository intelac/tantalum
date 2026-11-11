package tantalum.value.decision;

import java.util.Objects;
import tantalum.value.event.QueueChanged;
import tantalum.value.id.RunRef;

/**
 * 内核处理完一次 {@link QueueChanged}：{@code index} 是它实际动的那一条在处理前队列里的位置，没找到（或带附件不能改）为 -1。
 * 投影靠它知道动的是哪一条，不用自己再找一遍。{@code run} 是当时的活动运行。
 */
public record QueueEdited(RunRef run, QueueChanged.Action action, int index) implements Fact {

    public QueueEdited {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(action, "action");
    }

    @Override
    public String type() {
        return "QueueEdited";
    }
}
