package tantalum.value.id;

import java.util.Objects;

/** 提交请求的用户。 */
public record Principal(String value) {

    public Principal {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Principal must not be blank");
        }
    }
}
