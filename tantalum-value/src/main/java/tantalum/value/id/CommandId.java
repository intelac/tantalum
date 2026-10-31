package tantalum.value.id;

import java.util.Objects;

/** 内核发出的一条命令。 */
public record CommandId(String value) {

    public CommandId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("CommandId must not be blank");
        }
    }
}
