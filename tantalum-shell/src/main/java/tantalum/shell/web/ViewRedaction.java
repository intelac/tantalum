package tantalum.shell.web;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

/**
 * 管理员只读查看别人时遮掉个人插件的内容（memory-final-plan 第 13 节，Yi 2026-11-15 定）。在输出 JSON 的地方统一做，
 * 不靠每个接口各自记得：
 * <ol>
 *   <li>对象有 {@code plugin} 属于个人插件、又有 {@code text}：文字换成 {@value #HIDDEN}（插件附加的块）；</li>
 *   <li>对象有 {@code name} 是个人插件的工具：参数和结果换掉，记下它的调用编号（{@code id} 或 {@code call}）；</li>
 *   <li>对象有 {@code toolCallId} 是记下的编号、又有 {@code text}：结果换掉；</li>
 *   <li>对象有 {@code name} 是个人插件的段名、又有 {@code body}：内容换掉；</li>
 *   <li>任何字符串里的 {@code <段名>…</段名>} 和 {@code <attached plugin="个人插件">…</attached>}：内容换掉。</li>
 * </ol>
 * 模型在自己的回答里复述的记忆遮不掉（界面上写明）。
 */
final class ViewRedaction {

    static final String HIDDEN = "[hidden]";

    private final Set<String> plugins;
    private final Set<String> tools;
    private final Set<String> sections;
    private final List<Pattern> patterns;

    ViewRedaction(Set<String> plugins, Set<String> tools, Set<String> sections) {
        this.plugins = Set.copyOf(plugins);
        this.tools = Set.copyOf(tools);
        this.sections = Set.copyOf(sections);
        this.patterns = new java.util.ArrayList<>(sections.stream()
                .map(name -> Pattern.compile("(<" + Pattern.quote(name) + ">\\n)[\\s\\S]*?(\\n</" + Pattern.quote(name) + ">)")).toList());
        plugins.forEach(plugin -> patterns.add(Pattern.compile("(<attached plugin=\"" + Pattern.quote(plugin) + "\">\\n?)[\\s\\S]*?(\\n?</attached>)")));
    }

    /** 没有个人插件时什么都不做。 */
    boolean idle() {
        return plugins.isEmpty() && sections.isEmpty() && tools.isEmpty();
    }

    /** 原地改写并返回同一个节点。 */
    JsonNode redact(JsonNode root) {
        if (idle() || root == null) {
            return root;
        }
        Set<String> calls = new HashSet<>();
        collect(root, calls);
        return rewrite(root, calls);
    }

    String redactText(String text) {
        String result = text;
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(result);
            if (matcher.find()) {
                result = matcher.replaceAll(match -> Matcher.quoteReplacement(match.group(1) + HIDDEN + match.group(2)));
            }
        }
        return result;
    }

    private void collect(JsonNode node, Set<String> calls) {
        if (node instanceof ObjectNode object) {
            if (isHiddenTool(object)) {
                for (String key : List.of("id", "call", "toolCallId")) {
                    JsonNode id = object.get(key);
                    if (id != null && id.isString()) {
                        calls.add(id.asString());
                    }
                }
            }
            for (Map.Entry<String, JsonNode> field : object.properties()) {
                collect(field.getValue(), calls);
            }
        } else if (node instanceof ArrayNode array) {
            array.forEach(item -> collect(item, calls));
        }
    }

    private JsonNode rewrite(JsonNode node, Set<String> calls) {
        if (node instanceof ObjectNode object) {
            if (plugins.contains(text(object, "plugin")) && object.has("text")) {
                object.put("text", HIDDEN);
            }
            if (isHiddenTool(object)) {
                if (object.has("arguments")) {
                    object.putObject("arguments");
                }
                if (object.has("rawArguments")) {
                    object.put("rawArguments", "{}");
                }
                if (object.has("result") && object.get("result").isString()) {
                    object.put("result", HIDDEN);
                }
            }
            if (calls.contains(text(object, "toolCallId")) && object.has("text")) {
                object.put("text", HIDDEN);
            }
            if (sections.contains(text(object, "name"))) {
                // 上下文里的一段（body），和轨迹里“上下文变了”的记录（before / after）：都是这一段的原文
                for (String field : List.of("body", "before", "after")) {
                    if (object.has(field) && object.get(field).isString()) {
                        object.put(field, HIDDEN);
                    }
                }
            }
            Iterator<Map.Entry<String, JsonNode>> fields = object.properties().iterator();
            List<Map.Entry<String, JsonNode>> copy = new java.util.ArrayList<>();
            fields.forEachRemaining(copy::add);
            for (Map.Entry<String, JsonNode> field : copy) {
                JsonNode value = field.getValue();
                if (value.isString()) {
                    String changed = redactText(value.asString());
                    if (!changed.equals(value.asString())) {
                        object.set(field.getKey(), StringNode.valueOf(changed));
                    }
                } else {
                    rewrite(value, calls);
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                JsonNode item = array.get(i);
                if (item.isString()) {
                    String changed = redactText(item.asString());
                    if (!changed.equals(item.asString())) {
                        array.set(i, StringNode.valueOf(changed));
                    }
                } else {
                    rewrite(item, calls);
                }
            }
        }
        return node;
    }

    /** 字段是字符串时的值，否则为空串（集合里没有空串，比较安全）。 */
    private static String text(ObjectNode object, String key) {
        JsonNode value = object.get(key);
        return value != null && value.isString() ? value.asString() : "";
    }

    private boolean isHiddenTool(ObjectNode object) {
        return tools.contains(text(object, "name"))
                && (object.has("arguments") || object.has("rawArguments") || object.has("result"));
    }
}
