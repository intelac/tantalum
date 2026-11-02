package tantalum.value.id;

import java.util.Objects;
import java.util.Optional;

/** 一次运行。ID 是 {@code <会话ID>_<序号>}，所以能直接得到会话；序号在最后一个 {@code _} 之后，会话 ID 里可以有 {@code _}。 */
public record RunId(String value) {

    private static final char SEPARATOR = '_';

    public RunId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("RunId must not be blank");
        }
    }

    public static RunId of(SessionId session, long seq) {
        return new RunId(session.value() + SEPARATOR + seq);
    }

    /** 格式不对时为空。 */
    public Optional<SessionId> session() {
        int separator = value.lastIndexOf(SEPARATOR);
        return seq() < 0 || value.substring(0, separator).isBlank()
                ? Optional.empty()
                : Optional.of(new SessionId(value.substring(0, separator)));
    }

    /** 格式不对时为 -1。 */
    public long seq() {
        String digits = value.substring(value.lastIndexOf(SEPARATOR) + 1);
        if (value.indexOf(SEPARATOR) < 0 || digits.isEmpty() || !digits.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return -1;
        }
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException tooLong) {
            return -1;
        }
    }
}
