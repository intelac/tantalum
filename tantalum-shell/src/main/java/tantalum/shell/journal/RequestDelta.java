package tantalum.shell.journal;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import tantalum.value.command.CallModel;
import tantalum.value.id.CommandId;
import tantalum.value.id.RunRef;
import tantalum.value.model.Message;
import tantalum.value.model.ModelChoice;
import tantalum.value.model.Prompt;
import tantalum.value.model.ToolSpec;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 结果行里的模型调用（从 {@code RecordCodec} 拆出）：system 和工具和上一次相同时不写；
 * 消息写和上一次相同的前几条有多少（{@code keep}）和之后的（{@code add}），后者能指向历史里的消息时写下标；
 * 工具和会话上下文里相同时写下标。模型选择和上一次相同时不写，变成没有时写 null。另记原样请求的指纹。
 */
final class RequestDelta {

    /** 请求里的消息往回找多少条历史。 */
    private static final int RECENT = 64;
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() { };

    private final JsonMapper mapper;
    private final JsonMapper events;

    /** {@code events} 是 journal 和接口共用的 mapper，指纹用它编码。 */
    RequestDelta(JsonMapper mapper, JsonMapper events) {
        this.mapper = mapper;
        this.events = events;
    }

    /** 请求的指纹：用 journal 和接口的同一个 mapper 编码后取 SHA-256，只覆盖 {@link Prompt}，不含请求头和凭据。 */
    String fingerprint(Prompt prompt) {
        return RecordMemo.hash(new String(events.writeValueAsBytes(prompt), StandardCharsets.UTF_8));
    }

    ObjectNode encode(CallModel call, RecordMemo memo) {
        Prompt prompt = call.prompt();
        Prompt last = memo.lastPrompt();
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "CallModel");
        node.set("id", mapper.valueToTree(call.id()));
        node.set("run", mapper.valueToTree(call.run()));
        if (!call.omittedImages().isEmpty()) {
            node.set("omittedImages", mapper.valueToTree(call.omittedImages()));
        }
        ObjectNode written = node.putObject("prompt");
        if (last == null || !last.system().equals(prompt.system())) {
            written.put("system", prompt.system());
        }
        if (last == null || !last.tools().equals(prompt.tools())) {
            written.set("tools", tools(prompt.tools(), memo));
        }
        int keep = last == null ? 0 : commonPrefix(last.messages(), prompt.messages());
        written.put("keep", keep);
        written.set("add", messages(prompt.messages().subList(keep, prompt.messages().size()), memo));
        written.set("output", mapper.valueToTree(prompt.output()));
        ModelChoice before = last == null ? null : last.model();
        if (!Objects.equals(before, prompt.model())) {
            written.set("model", prompt.model() == null ? mapper.nullNode() : mapper.valueToTree(prompt.model()));
        }
        node.put("fingerprint", fingerprint(prompt));
        memo.lastPrompt(prompt);
        return node;
    }

    CallModel decode(JsonNode node, RecordMemo memo) {
        JsonNode written = node.required("prompt");
        Prompt last = memo.lastPrompt();
        int keep = written.required("keep").asInt();
        if ((last == null && (keep > 0 || !written.has("system") || !written.has("tools")))
                || (last != null && keep > last.messages().size())) {
            throw new IllegalArgumentException("request refers to an earlier request that is not in the file");
        }
        List<Message> messages = new ArrayList<>(keep == 0 ? List.of() : last.messages().subList(0, keep));
        messages.addAll(messages(written.required("add"), memo));
        Prompt prompt = new Prompt(written.has("system") ? written.get("system").asString() : last.system(),
                written.has("tools") ? tools(written.get("tools"), memo) : last.tools(),
                messages, mapper.treeToValue(written.required("output"), Prompt.Output.class), model(written, last));
        memo.lastPrompt(prompt);
        List<String> omitted = node.has("omittedImages") ? mapper.convertValue(node.get("omittedImages"), STRINGS) : List.of();
        return new CallModel(mapper.treeToValue(node.required("id"), CommandId.class),
                mapper.treeToValue(node.required("run"), RunRef.class), prompt, omitted);
    }

    /** 请求的模型选择：没写就和上一次相同。 */
    private ModelChoice model(JsonNode written, Prompt last) {
        if (!written.has("model")) {
            return last == null ? null : last.model();
        }
        JsonNode value = written.get("model");
        return value.isNull() ? null : mapper.treeToValue(value, ModelChoice.class);
    }

    /** 和会话上下文里相同的工具写成下标，标为 deferred 的写成 {@code {"deferred": 下标}}；钩子加的写原样。 */
    private ArrayNode tools(List<ToolSpec> tools, RecordMemo memo) {
        List<ToolSpec> known = memo.complete() && memo.context() != null ? memo.context().tools() : List.of();
        ArrayNode out = mapper.createArrayNode();
        for (ToolSpec tool : tools) {
            int index = known.indexOf(tool);
            int deferred = index < 0 && tool.deferred() ? known.indexOf(tool.deferred(false)) : -1;
            if (index >= 0) {
                out.add(index);
            } else if (deferred >= 0) {
                out.addObject().put("deferred", deferred);
            } else {
                out.add(mapper.valueToTree(tool));
            }
        }
        return out;
    }

    private List<ToolSpec> tools(JsonNode node, RecordMemo memo) {
        List<ToolSpec> known = memo.context() == null ? List.of() : memo.context().tools();
        List<ToolSpec> tools = new ArrayList<>();
        for (JsonNode item : node) {
            if (item.isInt()) {
                tools.add(at(known, item.asInt(), "tool"));
            } else if (item.size() == 1 && item.has("deferred")) {
                tools.add(at(known, item.get("deferred").asInt(), "tool").deferred(true));
            } else {
                tools.add(mapper.treeToValue(item, ToolSpec.class));
            }
        }
        return tools;
    }

    /**
     * 请求里的消息大多就是历史里的消息对象（摘要调用带着很早的历史）：先按对象找，再在最近的历史里按值找，找到写下标。
     */
    private ArrayNode messages(List<Message> messages, RecordMemo memo) {
        List<Message> history = memo.complete() ? memo.messages() : List.of();
        ArrayNode out = mapper.createArrayNode();
        for (Message message : messages) {
            int index = memo.complete() ? memo.position(message) : -1;
            if (index < 0) {
                index = recent(history, message);
            }
            if (index >= 0) {
                out.add(index);
            } else {
                out.add(mapper.valueToTree(message));
            }
        }
        return out;
    }

    private List<Message> messages(JsonNode node, RecordMemo memo) {
        List<Message> messages = new ArrayList<>();
        for (JsonNode item : node) {
            messages.add(item.isInt() ? at(memo.messages(), item.asInt(), "message") : mapper.treeToValue(item, Message.class));
        }
        return messages;
    }

    private static int recent(List<Message> history, Message message) {
        for (int i = history.size() - 1; i >= Math.max(0, history.size() - RECENT); i--) {
            if (history.get(i) == message || history.get(i).equals(message)) {
                return i;
            }
        }
        return -1;
    }

    private static <T> T at(List<T> items, int index, String what) {
        if (index < 0 || index >= items.size()) {
            throw new IllegalArgumentException(what + " " + index + " is not earlier in the file");
        }
        return items.get(index);
    }

    private static int commonPrefix(List<Message> a, List<Message> b) {
        int n = Math.min(a.size(), b.size());
        int i = 0;
        while (i < n && (a.get(i) == b.get(i) || a.get(i).equals(b.get(i)))) {
            i++;
        }
        return i;
    }
}
