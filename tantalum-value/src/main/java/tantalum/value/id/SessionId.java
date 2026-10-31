package tantalum.value.id;

import java.util.Objects;

/** 一个会话。 */
public record SessionId(String value) {

    public SessionId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("SessionId must not be blank");
        }
    }
}
