package tantalum.value.id;

import java.util.Objects;

/** 调用方提供的幂等键。 */
public record RequestId(String value) {

    public RequestId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("RequestId must not be blank");
        }
    }
}
