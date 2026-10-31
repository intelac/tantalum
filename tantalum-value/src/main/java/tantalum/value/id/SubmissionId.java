package tantalum.value.id;

import java.util.Objects;

/** 一次已接受的提交。 */
public record SubmissionId(String value) {

    public SubmissionId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("SubmissionId must not be blank");
        }
    }
}
