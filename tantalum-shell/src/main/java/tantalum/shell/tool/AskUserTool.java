package tantalum.shell.tool;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.ToolDispatch;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.ToolOutput;
import tantalum.value.json.Json;
import tantalum.value.question.AskUser;

/**
 * 内置工具 {@code ask_user}：只有给模型的定义。调用由内核截下，运行停在等人阶段，回答写成工具结果
 * （{@code tantalum.value.question.AskUser}）；这里永远不会被执行。插件页的开关和其他内置工具一样。
 */
public final class AskUserTool implements Tool {

    @Override public String name() { return AskUser.NAME; }

    @Override public String description() {
        return "Ask the user a question and wait for the answer. Use it only when you cannot continue without their decision; "
                + "it waits as long as needed, hours or days. Offer choices when there are natural options; the user can also "
                + "type a free-text answer unless you set allow_text to false. The result is {\"choice\": ..., \"text\": ...}, "
                + "or {\"timeout\": true} if timeout_seconds passed without an answer.";
    }

    /** 手写 schema：{@code choices} 是字符串数组，Claude 要求数组写明 {@code items}。 */
    @Override public Json schema() {
        Map<String, Json> choices = new LinkedHashMap<>();
        choices.put("type", new Json.Str("array"));
        choices.put("items", new Json.Obj(Map.of("type", new Json.Str("string"))));
        choices.put("minItems", new Json.Num(BigDecimal.ONE));
        choices.put("maxItems", new Json.Num(BigDecimal.valueOf(AskUser.MAX_CHOICES)));
        choices.put("description", new Json.Str("1 to " + AskUser.MAX_CHOICES + " short options, distinct strings. Put the recommended one first."));
        Map<String, Json> properties = new LinkedHashMap<>();
        properties.put("question", property("string", "The question, in the language of the conversation."));
        properties.put("choices", new Json.Obj(choices));
        properties.put("allow_text", property("boolean", "Whether the user may type a free-text answer. Default true."));
        properties.put("timeout_seconds", property("integer", "Give up after this many seconds. Omit to wait as long as needed."));
        Map<String, Json> schema = new LinkedHashMap<>();
        schema.put("type", new Json.Str("object"));
        schema.put("properties", new Json.Obj(properties));
        schema.put("required", new Json.Arr(List.of(new Json.Str("question"))));
        schema.put("additionalProperties", new Json.Bool(false));
        return new Json.Obj(schema);
    }

    private static Json property(String type, String description) {
        Map<String, Json> property = new LinkedHashMap<>();
        property.put("type", new Json.Str(type));
        property.put("description", new Json.Str(description));
        return new Json.Obj(property);
    }

    /** 由内核处理，不真的执行；独占。 */
    static final ToolDispatch DISPATCH = ToolDispatch.exclusive(AskUser.NAME, "questions", 1);

    @Override public String server() { return DISPATCH.server(); }
    @Override public ToolDispatch dispatch() { return DISPATCH; }

    @Override
    public ToolOutput run(ToolContext context, Json arguments) {
        throw new IllegalStateException("ask_user is handled by the kernel, not by the tool handler");
    }
}
