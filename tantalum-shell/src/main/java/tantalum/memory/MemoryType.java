package tantalum.memory;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/** 记忆条目的类型，决定模型什么时候该查它，不决定存在哪。 */
public enum MemoryType {
    /** 用户是谁、偏好什么。 */
    USER,
    /** 用户给过的纠正和确认过的做法。 */
    FEEDBACK,
    /** 项目事实：约束、目标、不能从代码里看出来的背景。 */
    PROJECT,
    /** 外部引用：链接、工单、看板。 */
    REFERENCE;

    /** 小写名字，文件和接口里都用它。 */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<MemoryType> parse(String key) {
        if (key == null) {
            return Optional.empty();
        }
        for (MemoryType type : values()) {
            if (type.key().equals(key.trim().toLowerCase(Locale.ROOT))) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }

    /**
     * 记忆账上页面的类型（memory-final-plan 第 6 节的六种）。旧的四种映射过去：user → person、feedback → preference、
     * project → project、reference → term。
     */
    public String pageType() {
        return switch (this) {
            case USER -> "person";
            case FEEDBACK -> "preference";
            case PROJECT -> "project";
            case REFERENCE -> "term";
        };
    }

    /** 从页面类型回到这四种（decision 归 project，procedure 归 feedback）；不认识的为空。 */
    public static Optional<MemoryType> fromPageType(String pageType) {
        return switch (pageType == null ? "" : pageType) {
            case "person" -> Optional.of(USER);
            case "preference", "procedure" -> Optional.of(FEEDBACK);
            case "project", "decision" -> Optional.of(PROJECT);
            case "term" -> Optional.of(REFERENCE);
            default -> Optional.empty();
        };
    }

    /** 全部类型的小写名字，逗号分隔，给模型和错误信息看。 */
    public static String keys() {
        return Arrays.stream(values()).map(MemoryType::key).collect(Collectors.joining(", "));
    }
}
