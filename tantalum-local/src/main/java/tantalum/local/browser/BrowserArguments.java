package tantalum.local.browser;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import tantalum.value.json.Json;

/** 读浏览器工具的参数，查格式。不合格时抛 {@link IllegalArgumentException}，消息直接给模型。 */
public final class BrowserArguments {

    /** 快照里的元素编号：{@code e5}，框架里的是 {@code f1e5}。 */
    private static final Pattern REF = Pattern.compile("(f\\d+)?e\\d+");

    private BrowserArguments() {}

    public static String required(Json arguments, String name) {
        return optional(arguments, name).orElseThrow(() -> new IllegalArgumentException("Missing argument: " + name));
    }

    public static Optional<String> optional(Json arguments, String name) {
        return arguments instanceof Json.Obj object && object.fields().get(name) instanceof Json.Str value
                ? Optional.of(value.value()) : Optional.empty();
    }

    public static boolean flag(Json arguments, String name) {
        return arguments instanceof Json.Obj object && object.fields().get(name) instanceof Json.Bool value && value.value();
    }

    public static Optional<Double> number(Json arguments, String name) {
        return arguments instanceof Json.Obj object && object.fields().get(name) instanceof Json.Num value
                ? Optional.of(value.value().doubleValue()) : Optional.empty();
    }

    /** {@code values}：至少一个字符串。 */
    public static List<String> values(Json arguments) {
        if (arguments instanceof Json.Obj object && object.fields().get("values") instanceof Json.Arr array) {
            List<String> values = new ArrayList<>();
            for (Json item : array.items()) {
                if (item instanceof Json.Str value) {
                    values.add(value.value());
                }
            }
            if (!values.isEmpty()) {
                return values;
            }
        }
        throw new IllegalArgumentException("Missing argument: values");
    }

    /** 元素编号。长得不像编号的和页面上没有的说同一句话，模型只要知道该重新拿快照。 */
    public static String ref(Json arguments) {
        String ref = required(arguments, "ref");
        if (!REF.matcher(ref).matches()) {
            throw new IllegalArgumentException(staleRef(ref));
        }
        return ref;
    }

    public static String staleRef(String ref) {
        return "Element ref " + ref + " is not on the current page; call browser_snapshot for fresh refs.";
    }

    public static void checkWait(Json arguments) {
        Optional<Double> seconds = number(arguments, "seconds");
        if (optional(arguments, "text").isEmpty() && optional(arguments, "textGone").isEmpty() && seconds.isEmpty()) {
            throw new IllegalArgumentException("Give text, textGone or seconds.");
        }
        if (seconds.isPresent() && (seconds.get() < 0 || seconds.get() > 30)) {
            throw new IllegalArgumentException("seconds must be between 0 and 30.");
        }
    }

    public static void checkTabs(Json arguments) {
        String action = required(arguments, "action");
        if (!action.equals("list") && !action.equals("select")) {
            throw new IllegalArgumentException("action must be list or select.");
        }
        if (action.equals("select") && number(arguments, "index").isEmpty()) {
            throw new IllegalArgumentException("Give the index of the tab to select (1 is the first tab).");
        }
    }
}
