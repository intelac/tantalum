package tantalum.value.decision;

import java.util.Objects;
import tantalum.value.id.RunRef;
import tantalum.value.state.Compacting;

/** 压缩没有采用，视图不变。{@code reason} 是给人看的原因。 */
public record CompactionFailed(RunRef run, Compacting.Trigger trigger, String reason) implements Fact {

    public CompactionFailed {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(trigger, "trigger");
        Objects.requireNonNull(reason, "reason");
    }

    @Override
    public String type() {
        return "CompactionFailed";
    }
}
