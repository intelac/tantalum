package tantalum.value.delivery;

import java.util.Objects;
import tantalum.value.event.AssignmentCancelled;

/** 叫停一次交代，送到接它的会话；排在那次交代后面送（delivery 需求 1.2）。 */
public record StopParcel(AssignmentCancelled input) implements Parcel {

    public StopParcel {
        Objects.requireNonNull(input, "input");
    }

    @Override
    public String id() {
        return "stop:" + input.assignment();
    }

    @Override
    public String assignment() {
        return input.assignment();
    }
}
