package tantalum.shell.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tantalum.shell.json.JsonText;
import tantalum.value.model.ToolCallBlock;
import tools.jackson.databind.json.JsonMapper;

/**
 * 写在正文里的工具调用（2026-11-16 LongMemEval：本机 qwen 有 3 道题把工具调用按 Qwen3 的写法
 * {@code <tool_call><function=名字><parameter=参数>值</parameter></function></tool_call>} 写进了正文，模型服务没解析，
 * 这一轮就以这段文字当回答结束了）。只在整段正文全是完整的调用块时才认，夹着别的文字照旧是文字。名字不查：
 * 三次里调的都是这次没给的 {@code web_search}，照调用处理，工具那边回“Unknown tool”，模型接着答，比把这段当回答好。
 * 参数值是 JSON（数字、数组、真假）就按 JSON，不是就当字符串。
 */
final class TextToolCalls {

    private static final Pattern BLOCK = Pattern.compile("<tool_call>\\s*<function=([A-Za-z0-9_.-]{1,64})>(.*?)</function>\\s*</tool_call>",
            Pattern.DOTALL);
    private static final Pattern PARAMETER = Pattern.compile("<parameter=([A-Za-z0-9_.-]{1,64})>(.*?)</parameter>", Pattern.DOTALL);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_CALLS = 8;

    private TextToolCalls() {}

    /** 正文全是调用块时，按顺序给出调用；id 是 {@code text-call-<序号>-<前缀>}。 */
    static Optional<List<ToolCallBlock>> parse(String text, String prefix) {
        String body = text == null ? "" : text.strip();
        if (!body.startsWith("<tool_call>") || !body.endsWith("</tool_call>")) {
            return Optional.empty();
        }
        Matcher block = BLOCK.matcher(body);
        List<ToolCallBlock> calls = new ArrayList<>();
        int end = 0;
        while (block.find()) {
            if (!body.substring(end, block.start()).isBlank() || calls.size() >= MAX_CALLS) {
                return Optional.empty();
            }
            Map<String, Object> arguments = new LinkedHashMap<>();
            Matcher parameter = PARAMETER.matcher(block.group(2));
            int inner = 0;
            while (parameter.find()) {
                if (!block.group(2).substring(inner, parameter.start()).isBlank()) {
                    return Optional.empty();
                }
                arguments.put(parameter.group(1), value(parameter.group(2).strip()));
                inner = parameter.end();
            }
            if (!block.group(2).substring(inner).isBlank()) {
                return Optional.empty();
            }
            String raw = JSON.writeValueAsString(arguments);
            calls.add(new ToolCallBlock("text-call-" + (calls.size() + 1) + "-" + prefix, block.group(1), raw, JsonText.parseOrNull(raw)));
            end = block.end();
        }
        return calls.isEmpty() || !body.substring(end).isBlank() ? Optional.empty() : Optional.of(List.copyOf(calls));
    }

    private static Object value(String text) {
        if (text.startsWith("[") || text.startsWith("{") || text.equals("true") || text.equals("false") || text.matches("-?\\d+(\\.\\d+)?")) {
            try {
                return JSON.readValue(text, Object.class);
            } catch (RuntimeException notJson) {
                return text;
            }
        }
        return text;
    }
}
