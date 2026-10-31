package tantalum.value.decision;

import java.util.Objects;
import tantalum.value.id.RunRef;

/** 运行进入了新状态，{@code phase} 为状态名。 */
public record PhaseChanged(RunRef run, String phase) implements Fact {

    public PhaseChanged {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(phase, "phase");
    }

    @Override
    public String type() {
        return "PhaseChanged";
    }
}
