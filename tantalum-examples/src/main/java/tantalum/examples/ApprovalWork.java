package tantalum.examples;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tantalum.shell.plugin.Question;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.WorkResult;
import tantalum.shell.plugin.WorkType;
import tantalum.value.json.Json;

/**
 * {@link ToolApprovalHook} 要的工作：在对话页上问用户“允许调用这个工具吗”，三个选项：允许这一次、这个会话里一直允许、拒绝。
 * 输入 {@code {"tool","arguments","call","poll"}}；最多等 {@code wait}（要比 {@code tantalum.tool-timeout} 短），没回答就回“待定”，
 * 钩子马上再问一次，问题一直留在页面上（同一个调用的问题 id 相同）。结果 {@code {"decision":"approved|denied|pending","scope":…}}
 * 原样带回工具、参数、调用和次数，钩子下一轮要用（失败的工作结果里没有原来的输入）。
 *
 * <p>选“这个会话里一直允许”时，结果带 {@code note {"工具名": true}}，内核把它记进会话状态（随 {@code WorkDone} 进 journal），
 * 钩子之后在这个会话里看到就不再问；别的会话照样问。
 */
public final class ApprovalWork implements WorkType {

    public static final String TYPE = "approval";
    static final String APPROVED = "approved";
    static final String DENIED = "denied";
    static final String PENDING = "pending";
    /** 三个选项的 id。 */
    static final String ONCE = "once";
    static final String SESSION = "session";
    static final String DENY = "deny";
    private static final int SHOWN_ARGUMENT_CHARS = 2000;

    private final Duration wait;

    public ApprovalWork(Duration wait) {
        if (wait == null || wait.isNegative() || wait.isZero()) {
            throw new IllegalArgumentException("wait must be positive but was " + wait);
        }
        this.wait = wait;
    }

    @Override
    public String name() {
        return TYPE;
    }

    @Override
    public String plugin() {
        return "toolApproval";
    }

    @Override
    public String description() {
        return "在对话页上问你：允许这一次、这个会话里一直允许，还是拒绝。";
    }

    @Override
    public WorkResult run(ToolContext context, Json input) throws InterruptedException {
        Map<String, Json> asked = input instanceof Json.Obj obj ? obj.fields() : Map.of();
        String tool = asked.get("tool") instanceof Json.Str name ? name.value() : "";
        String call = asked.get("call") instanceof Json.Str id ? id.value() : tool;
        Json arguments = asked.getOrDefault("arguments", new Json.Null());
        Question question = new Question("approval-" + call, "允许调用 " + tool + " 吗？", shown(arguments), List.of(
                new Question.Choice(ONCE, "允许这一次"),
                new Question.Choice(SESSION, "这个会话里一直允许"),
                new Question.Choice(DENY, "拒绝")));
        Optional<String> choice = context.questions().ask(context, question, wait);

        Map<String, Json> result = new LinkedHashMap<>();
        result.put("decision", new Json.Str(choice.map(id -> id.equals(DENY) ? DENIED : APPROVED).orElse(PENDING)));
        choice.filter(id -> !id.equals(DENY)).ifPresent(scope -> result.put("scope", new Json.Str(scope)));
        for (String key : new String[] {"tool", "arguments", "call", "poll"}) {
            result.put(key, asked.getOrDefault(key, key.equals("poll") ? new Json.Num(BigDecimal.ZERO) : new Json.Null()));
        }
        Json.Obj note = choice.filter(SESSION::equals).isPresent() ? new Json.Obj(Map.of(tool, new Json.Bool(true))) : null;
        return new WorkResult(new Json.Obj(result), null, note);
    }

    /** 页面上显示的参数：JSON 原文，太长时截断，不在代理对中间切开。 */
    private static String shown(Json arguments) {
        String text = write(arguments);
        if (text.length() <= SHOWN_ARGUMENT_CHARS) {
            return text;
        }
        int cut = Character.isHighSurrogate(text.charAt(SHOWN_ARGUMENT_CHARS - 1)) ? SHOWN_ARGUMENT_CHARS - 1 : SHOWN_ARGUMENT_CHARS;
        return text.substring(0, cut) + "…";
    }

    /** 把 {@link Json} 写成 JSON 文本（插件 SDK 不带 JSON 库）。 */
    static String write(Json value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    private static void write(Json value, StringBuilder out) {
        switch (value) {
            case Json.Null ignored -> out.append("null");
            case Json.Bool bool -> out.append(bool.value());
            case Json.Num number -> out.append(number.value().toString());
            case Json.Str text -> quote(text.value(), out);
            case Json.Arr array -> {
                out.append('[');
                for (int i = 0; i < array.items().size(); i++) {
                    out.append(i == 0 ? "" : ",");
                    write(array.items().get(i), out);
                }
                out.append(']');
            }
            case Json.Obj obj -> {
                out.append('{');
                boolean first = true;
                for (Map.Entry<String, Json> field : obj.fields().entrySet()) {
                    out.append(first ? "" : ",");
                    first = false;
                    quote(field.getKey(), out);
                    out.append(':');
                    write(field.getValue(), out);
                }
                out.append('}');
            }
        }
    }

    private static void quote(String text, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
