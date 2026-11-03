package tantalum.examples;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.WorkResult;
import tantalum.shell.plugin.WorkType;
import tantalum.value.json.Json;

/**
 * {@link ToolApprovalHook} 要的工作：把 {@code {"tool","arguments","poll"}} POST 给审批服务，服务回 {@code {"decision":"approved|denied|pending"}}。
 * 待定时钩子会马上再问一次，所以服务应当先等一会儿（长轮询）再回待定。结果原样带回工具、参数和次数，钩子下一轮要用。
 * 认不出的回复按拒绝处理，原因里带上原文前 200 个字：审批没有明确同意就不能执行。连不上时抛异常，工作失败，钩子同样拦截。
 */
public final class ApprovalWork implements WorkType {

    public static final String TYPE = "approval";
    static final String APPROVED = "approved";
    static final String DENIED = "denied";
    static final String PENDING = "pending";

    /** 插件 SDK 不带 JSON 库，回复只认这一个字段；前面不能是反斜杠，否则是别的字符串里转义的引号。 */
    private static final Pattern DECISION = Pattern.compile("(?<!\\\\)\"decision\"\\s*:\\s*\"(approved|denied|pending)\"");
    private static final int QUOTED_CHARS = 200;

    private final URI service;
    private final HttpClient http = HttpClient.newHttpClient();

    public ApprovalWork(URI service) {
        this.service = Objects.requireNonNull(service, "service");
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
    public WorkResult run(ToolContext context, Json input) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(service)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(write(input)))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        Matcher matcher = DECISION.matcher(response.body());
        boolean ok = response.statusCode() / 100 == 2 && matcher.find();

        Map<String, Json> result = new LinkedHashMap<>();
        result.put("decision", new Json.Str(ok ? matcher.group(1) : DENIED));
        Map<String, Json> asked = input instanceof Json.Obj obj ? obj.fields() : Map.of();
        for (String key : new String[] {"tool", "arguments", "poll"}) {
            result.put(key, asked.getOrDefault(key, new Json.Null()));
        }
        if (!ok) {
            result.put("reason", new Json.Str("unrecognised reply (HTTP " + response.statusCode() + "): " + quoted(response.body())));
        }
        return new WorkResult(new Json.Obj(result), null);
    }

    /** 原文前 200 个字，不在代理对中间切开，否则写进 journal 的是一个坏字符。 */
    private static String quoted(String body) {
        if (body.length() <= QUOTED_CHARS) {
            return body;
        }
        int cut = Character.isHighSurrogate(body.charAt(QUOTED_CHARS - 1)) ? QUOTED_CHARS - 1 : QUOTED_CHARS;
        return body.substring(0, cut);
    }

    /** 把 {@link Json} 写成 JSON 文本。 */
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
