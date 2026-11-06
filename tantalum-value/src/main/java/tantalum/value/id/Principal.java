package tantalum.value.id;

import java.util.Objects;
import java.util.regex.Pattern;

/** 提交请求的用户。 */
public record Principal(String value) {

    private static final Pattern USABLE = Pattern.compile("[a-z0-9_-]{1,64}");

    public Principal {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Principal must not be blank");
        }
    }

    /** 能注册成用户名：可以直接用作目录名，不含路径分隔符，也不可能是 {@code ..} 或 Windows 的设备名（{@link ReservedName}）。 */
    public static boolean isUsable(String name) {
        return name != null && USABLE.matcher(name).matches() && !ReservedName.matches(name);
    }
}
