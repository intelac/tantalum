package tantalum.value.id;

import java.util.Objects;

/** 一次运行。 */
public record RunId(String value) {

    public RunId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("RunId must not be blank");
        }
    }
}
