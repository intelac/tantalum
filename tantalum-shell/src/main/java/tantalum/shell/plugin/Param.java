package tantalum.shell.plugin;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import tantalum.value.json.Json;
import tantalum.value.text.LocalText;

/**
 * 插件的一个参数：插件页照它画表单，值按人存在服务器上。用工厂方法造，再接 {@link #label}、{@link #hint}、{@link #range}、
 * {@link #adminOnly}，每个都返回新的 {@code Param}。
 *
 * <p>{@code byDefault} 是插件自带的默认值（密码没有，为 null）；{@code min}、{@code max} 只对数字有意义，null 表示不限。
 * {@code owner} 是谁能改：用户自己，或只有管理员（管理员可以给每个人定不同的值，比如额度）。
 */
public record Param(String key, Type type, LocalText label, LocalText hint, Json byDefault, Long min, Long max, Owner owner) {

    public enum Type { TEXT, NUMBER, TOGGLE, LIST, SECRET }

    public enum Owner { USER, ADMIN }

    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    public Param {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(hint, "hint");
        Objects.requireNonNull(owner, "owner");
        if (!KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("param key must be 1-64 letters, digits, '_' or '-' but was: " + key);
        }
        if (type == Type.SECRET) {
            if (byDefault != null) {
                throw new IllegalArgumentException("secret param " + key + " cannot have a default");
            }
            if (owner == Owner.ADMIN) {
                throw new IllegalArgumentException("secret param " + key + " cannot be admin-only");
            }
        } else {
            Objects.requireNonNull(byDefault, "byDefault");
        }
        if (type != Type.NUMBER && (min != null || max != null)) {
            throw new IllegalArgumentException("param " + key + " is not a number and cannot have a range");
        }
        if (type == Type.NUMBER) {
            if (min != null && max != null && min > max) {
                throw new IllegalArgumentException("param " + key + " has min " + min + " above max " + max);
            }
            long value = ((Json.Num) byDefault).value().longValueExact();
            if (min != null && value < min || max != null && value > max) {
                throw new IllegalArgumentException("param " + key + " has default " + value + " outside its range");
            }
        }
    }

    public static Param text(String key, String byDefault) {
        return new Param(key, Type.TEXT, LocalText.empty(), LocalText.empty(), new Json.Str(byDefault), null, null, Owner.USER);
    }

    public static Param number(String key, long byDefault) {
        return new Param(key, Type.NUMBER, LocalText.empty(), LocalText.empty(), new Json.Num(BigDecimal.valueOf(byDefault)),
                null, null, Owner.USER);
    }

    public static Param toggle(String key, boolean byDefault) {
        return new Param(key, Type.TOGGLE, LocalText.empty(), LocalText.empty(), new Json.Bool(byDefault), null, null, Owner.USER);
    }

    /** 多行清单，每行一项。 */
    public static Param list(String key, List<String> byDefault) {
        return new Param(key, Type.LIST, LocalText.empty(), LocalText.empty(),
                new Json.Arr(byDefault.stream().<Json>map(Json.Str::new).toList()), null, null, Owner.USER);
    }

    /** 密码：加密存在服务器上，页面和记录里都看不到；只有工具、工具后处理、插件工作在调用时能取到。 */
    public static Param secret(String key) {
        return new Param(key, Type.SECRET, LocalText.empty(), LocalText.empty(), null, null, null, Owner.USER);
    }

    /** 插件页上的名字；不写就显示键。 */
    public Param label(LocalText text) {
        return new Param(key, type, text, hint, byDefault, min, max, owner);
    }

    /** 输入框下面的一句说明。 */
    public Param hint(LocalText text) {
        return new Param(key, type, label, text, byDefault, min, max, owner);
    }

    /** 数字的上下限（含两端）。 */
    public Param range(long lowest, long highest) {
        return new Param(key, type, label, hint, byDefault, lowest, highest, owner);
    }

    /** 数字的下限。 */
    public Param atLeast(long lowest) {
        return new Param(key, type, label, hint, byDefault, lowest, max, owner);
    }

    /** 只有管理员能改，可以给每个人定不同的值。 */
    public Param adminOnly() {
        return new Param(key, type, label, hint, byDefault, min, max, Owner.ADMIN);
    }

    public boolean secret() {
        return type == Type.SECRET;
    }
}
