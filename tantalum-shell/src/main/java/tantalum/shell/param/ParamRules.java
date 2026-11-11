package tantalum.shell.param;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import tantalum.shell.plugin.Param;
import tantalum.value.json.Json;

/** 一个值合不合参数的声明。合格的返回规范化后的值（文字去首尾空白，清单去空行），不合格抛 {@link InvalidParam}。 */
public final class ParamRules {

    public static final int TEXT_MAX = 2000;
    public static final int LIST_MAX = 200;
    public static final int ITEM_MAX = 500;
    public static final int SECRET_MAX = 500;

    private ParamRules() {}

    /** 值不合格。{@code reason} 是 {@code type}、{@code range}、{@code tooLong}、{@code tooMany}、{@code unknown} 之一，页面按它显示一句话；消息里不带值。 */
    public static final class InvalidParam extends IllegalArgumentException {
        private final String key;
        private final String reason;

        public InvalidParam(String key, String reason) {
            super("param " + key + " is invalid: " + reason);
            this.key = key;
            this.reason = reason;
        }

        public String key() {
            return key;
        }

        public String reason() {
            return reason;
        }
    }

    public static Json check(Param param, Json value) {
        return switch (param.type()) {
            case TEXT -> {
                if (!(value instanceof Json.Str text)) {
                    throw new InvalidParam(param.key(), "type");
                }
                String trimmed = text.value().strip();
                if (trimmed.length() > TEXT_MAX) {
                    throw new InvalidParam(param.key(), "tooLong");
                }
                yield new Json.Str(trimmed);
            }
            case NUMBER -> {
                if (!(value instanceof Json.Num number)) {
                    throw new InvalidParam(param.key(), "type");
                }
                long whole;
                try {
                    whole = number.value().longValueExact();
                } catch (ArithmeticException notWhole) {
                    throw new InvalidParam(param.key(), number.value().stripTrailingZeros().scale() > 0 ? "type" : "range");
                }
                if (param.min() != null && whole < param.min() || param.max() != null && whole > param.max()) {
                    throw new InvalidParam(param.key(), "range");
                }
                yield new Json.Num(BigDecimal.valueOf(whole));
            }
            case TOGGLE -> {
                if (!(value instanceof Json.Bool)) {
                    throw new InvalidParam(param.key(), "type");
                }
                yield value;
            }
            case LIST -> {
                if (!(value instanceof Json.Arr array)) {
                    throw new InvalidParam(param.key(), "type");
                }
                List<Json> items = new ArrayList<>();
                for (Json item : array.items()) {
                    if (!(item instanceof Json.Str text)) {
                        throw new InvalidParam(param.key(), "type");
                    }
                    String trimmed = text.value().strip();
                    if (trimmed.isEmpty()) {
                        continue;
                    }
                    if (trimmed.length() > ITEM_MAX) {
                        throw new InvalidParam(param.key(), "tooLong");
                    }
                    items.add(new Json.Str(trimmed));
                }
                if (items.size() > LIST_MAX) {
                    throw new InvalidParam(param.key(), "tooMany");
                }
                yield new Json.Arr(items);
            }
            case SECRET -> {
                if (!(value instanceof Json.Str text) || text.value().isEmpty()) {
                    throw new InvalidParam(param.key(), "type");
                }
                if (text.value().length() > SECRET_MAX) {
                    throw new InvalidParam(param.key(), "tooLong");
                }
                yield value;
            }
        };
    }
}
