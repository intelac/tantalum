package tantalum.value.delivery;

import java.util.Objects;
import tantalum.value.event.Report;

/** 一次交代的结果，送回交代它的会话。 */
public record ReportParcel(Report input) implements Parcel {

    public ReportParcel {
        Objects.requireNonNull(input, "input");
    }

    @Override
    public String id() {
        return "report:" + input.assignment();
    }

    @Override
    public String assignment() {
        return input.assignment();
    }
}
