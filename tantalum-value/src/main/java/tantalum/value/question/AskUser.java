package tantalum.value.question;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tantalum.value.json.Json;
import tantalum.value.text.LocalText;

/**
 * 内置工具 {@code ask_user}：内核认它的名字，不发命令，把它变成一个等人的问题；回答写成工具结果。
 * 接口只放静态方法（value 模块的规则：只有 record、interface、enum）。参数：{@code question}（必填）、{@code choices}（可选，1–{@value #MAX_CHOICES} 个不重复的字符串）、{@code allow_text}（默认 true）、
 * {@code timeout_seconds}（可选，正整数）。这里只有纯函数，shell 里的 {@code AskUserTool} 负责给模型的定义。
 */
public interface AskUser {

    String NAME = "ask_user";
    int MAX_CHOICES = 8;
    /** 期限到了的工具结果。 */
    String TIMEOUT = "{\"timeout\":true}";

    /** 解析出的问题和最多等多久（null 为一直等）。 */
    record Parsed(Question question, Duration timeout) {}

    /** 一个调用的问题 id：同一个调用再问就是同一个问题。 */
    static String questionId(String callId) {
        return "ask-" + callId;
    }

    /** 参数不合法时抛 {@link IllegalArgumentException}，信息直接给模型。 */
    static Parsed parse(String callId, Json arguments) {
        Map<String, Json> fields = arguments instanceof Json.Obj obj ? obj.fields() : null;
        if (fields == null) {
            throw new IllegalArgumentException("ask_user needs an object with at least \"question\"");
        }
        String text = fields.get("question") instanceof Json.Str str ? str.value().strip() : "";
        if (text.isEmpty()) {
            throw new IllegalArgumentException("ask_user needs a non-empty \"question\"");
        }
        List<Question.Choice> choices = new ArrayList<>();
        Json rawChoices = fields.get("choices");
        if (rawChoices != null && !(rawChoices instanceof Json.Null)) {
            if (!(rawChoices instanceof Json.Arr array)) {
                throw new IllegalArgumentException("\"choices\" must be an array of strings");
            }
            for (Json item : array.items()) {
                String choice = item instanceof Json.Str str ? str.value().strip() : "";
                if (choice.isEmpty()) {
                    throw new IllegalArgumentException("\"choices\" must be non-empty strings");
                }
                if (choices.stream().anyMatch(existing -> existing.id().equals(choice))) {
                    throw new IllegalArgumentException("\"choices\" must be distinct: " + choice);
                }
                choices.add(new Question.Choice(choice, LocalText.of(choice)));
            }
            if (choices.isEmpty() || choices.size() > MAX_CHOICES) {
                throw new IllegalArgumentException("\"choices\" must have 1 to " + MAX_CHOICES + " entries");
            }
        }
        boolean allowText = true;
        Json rawAllow = fields.get("allow_text");
        if (rawAllow != null && !(rawAllow instanceof Json.Null)) {
            if (!(rawAllow instanceof Json.Bool bool)) {
                throw new IllegalArgumentException("\"allow_text\" must be true or false");
            }
            allowText = bool.value();
        }
        if (choices.isEmpty() && !allowText) {
            throw new IllegalArgumentException("ask_user needs \"choices\" when \"allow_text\" is false");
        }
        Duration timeout = null;
        Json rawTimeout = fields.get("timeout_seconds");
        if (rawTimeout != null && !(rawTimeout instanceof Json.Null)) {
            if (!(rawTimeout instanceof Json.Num number) || number.value().scale() > 0 || number.value().signum() <= 0
                    || number.value().compareTo(java.math.BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
                throw new IllegalArgumentException("\"timeout_seconds\" must be a positive integer");
            }
            timeout = Duration.ofSeconds(number.value().intValueExact());
        }
        return new Parsed(new Question(questionId(callId), LocalText.of(text), LocalText.empty(), choices, allowText, null), timeout);
    }

    /** 回答写成工具结果：{@code {"choice":"…","text":"…"}}，没有的字段省略。 */
    static String result(Answer answer) {
        StringBuilder out = new StringBuilder("{");
        if (answer.choice() != null) {
            out.append("\"choice\":");
            quote(answer.choice(), out);
        }
        if (answer.text() != null) {
            if (answer.choice() != null) {
                out.append(',');
            }
            out.append("\"text\":");
            quote(answer.text(), out);
        }
        return out.append('}').toString();
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
