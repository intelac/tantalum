package tantalum.value.decision;

import java.util.Objects;
import tantalum.value.id.RunRef;

/**
 * 内核处理完一次 {@link tantalum.value.event.TextWithdrawn}：{@code places} 是内存里换掉了几处（一个文字块里出现几次算几处）。
 * 压缩去掉的历史不在内存里，不算（账里的由读账的人换掉）。
 * {@code run} 是会话最近的一轮（撤下不属于哪一轮，这里只用来指明会话和用户）。一处都没换到时不报。
 */
public record TextSwapped(RunRef run, int places) implements Fact {

    public TextSwapped {
        Objects.requireNonNull(run, "run");
    }

    @Override
    public String type() {
        return "TextSwapped";
    }
}
