package tantalum.value.decision;

import java.util.Objects;
import tantalum.value.event.QueueChanged;
import tantalum.value.id.RunRef;

/**
 * 内核处理完一次 {@link QueueChanged}：{@code item} 是它实际动的那一条的编号，没找到（或那一条不能这样改）为 -1。
 * {@code run} 是当时的活动运行。
 */
public record QueueEdited(RunRef run, QueueChanged.Action action, long item) implements Fact {
    public QueueEdited {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(action, "action");
    }

    @Override
    public String type() {
        return "QueueEdited";
    }
}
