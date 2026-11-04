package tantalum.shell.journal;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tantalum.value.command.AbortCommands;
import tantalum.value.command.AssembleContext;
import tantalum.value.command.CallModel;
import tantalum.value.command.Command;
import tantalum.value.command.DoWork;
import tantalum.value.command.RunTool;
import tantalum.value.command.StartTimer;
import tantalum.value.decision.CompactionFailed;
import tantalum.value.decision.ContextCompacted;
import tantalum.value.decision.Fact;
import tantalum.value.decision.HookDecided;
import tantalum.value.decision.HookReported;
import tantalum.value.decision.ImagesOmitted;
import tantalum.value.decision.PhaseChanged;
import tantalum.value.decision.SessionChange;
import tantalum.value.decision.StepRecord;
import tantalum.value.id.CommandId;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;
import tantalum.value.model.Message;
import tantalum.value.model.Prompt;
import tantalum.value.model.TokenUsage;
import tantalum.value.model.ToolSpec;
import tantalum.value.state.Awaiting;
import tantalum.value.state.Compacting;
import tantalum.value.state.Compaction;
import tantalum.value.state.Deciding;
import tantalum.value.state.Ended;
import tantalum.value.state.RunPhase;
import tantalum.value.state.RunState;
import tantalum.value.state.SessionContext;
import tantalum.value.state.UsageAnchor;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.util.StdConverter;

/**
 * 结果行与 JSON 互转（record-results）。一行是 {@code {"result": 序号, ...}}，没变的部分不写。
 * 两处省掉重复：结果行里的长字符串在文件前面出现过就写成引用 {@code "\u0000#"+SHA-256}（字面以 {@code \u0000} 开头的
 * 字符串多加一个 {@code \u0000}）；模型调用的请求只写相对这个会话上一次请求的变化，另记原样请求的指纹。
 * 引用只指向更早的内容，按文件顺序读一遍就能全部解开。
 */
public final class RecordCodec {

    private static final char MARK = '\u0000';
    /** 请求里的消息往回找多少条历史。 */
    private static final int RECENT = 64;
    private static final String REF = MARK + "#";
    private static final TypeReference<List<Message>> MESSAGES = new TypeReference<>() { };
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() { };
    private static final TypeReference<List<RunId>> RUN_IDS = new TypeReference<>() { };
    private static final Set<String> FACTS = Set.of("PhaseChanged", "ContextCompacted", "CompactionFailed", "HookDecided",
            "HookReported", "ImagesOmitted");

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = AssembleContext.class, name = "AssembleContext"),
        @JsonSubTypes.Type(value = RunTool.class, name = "RunTool"),
        @JsonSubTypes.Type(value = DoWork.class, name = "DoWork"),
        @JsonSubTypes.Type(value = StartTimer.class, name = "StartTimer"),
        @JsonSubTypes.Type(value = AbortCommands.class, name = "AbortCommands")
    })
    private interface CommandMixin {}

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = PhaseChanged.class, name = "PhaseChanged"),
        @JsonSubTypes.Type(value = ContextCompacted.class, name = "ContextCompacted"),
        @JsonSubTypes.Type(value = CompactionFailed.class, name = "CompactionFailed"),
        @JsonSubTypes.Type(value = HookDecided.class, name = "HookDecided"),
        @JsonSubTypes.Type(value = HookReported.class, name = "HookReported"),
        @JsonSubTypes.Type(value = ImagesOmitted.class, name = "ImagesOmitted")
    })
    private interface FactMixin {}

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = Deciding.class, name = "Deciding"),
        @JsonSubTypes.Type(value = Awaiting.class, name = "Awaiting"),
        @JsonSubTypes.Type(value = Ended.class, name = "Ended")
    })
    private interface PhaseMixin {}

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = Compacting.Auto.class, name = "Auto"),
        @JsonSubTypes.Type(value = Compacting.Overflow.class, name = "Overflow"),
        @JsonSubTypes.Type(value = Compacting.Manual.class, name = "Manual")
    })
    private interface CauseMixin {}

    /** 集合按 ID 排序写出：{@code Set.of} 的顺序每个进程不同，同样的结果必须写成同样的字节。 */
    private interface SortedCommandsMixin {
        @JsonSerialize(converter = SortedIds.class)
        Set<CommandId> commands();
    }

    private static final class SortedIds extends StdConverter<Set<CommandId>, List<CommandId>> {
        @Override
        public List<CommandId> convert(Set<CommandId> ids) {
            return ids.stream().sorted((a, b) -> a.value().compareTo(b.value())).toList();
        }
    }

    /** 解出来的一行：结果，以及还原后的请求和当时记下的指纹不一致的模型调用（需求 4.2）。 */
    public record Decoded(StepRecord record, Set<CommandId> fingerprintMismatches) {}

    private final JsonMapper mapper;
    private final JsonMapper events;

    public RecordCodec(EventCodec codec) {
        this.events = codec.mapper();
        this.mapper = codec.mapper().rebuild()
                .addMixIn(Command.class, CommandMixin.class)
                .addMixIn(Fact.class, FactMixin.class)
                .addMixIn(RunPhase.class, PhaseMixin.class)
                .addMixIn(Compacting.Cause.class, CauseMixin.class)
                .addMixIn(Awaiting.class, SortedCommandsMixin.class)
                .addMixIn(AbortCommands.class, SortedCommandsMixin.class)
                // 状态里出现不认识的字段说明是更新的版本写的，不能当作没看见（需求 7.3）
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    /** 编码一行，并把这一行写过的长字符串和请求记进 {@code memo}。 */
    public String encode(StepRecord record, RecordMemo memo) {
        ObjectNode root = mapper.createObjectNode();
        root.put("result", record.seq());
        if (record.session() != null) {
            root.set("session", session(record.session(), memo));
        }
        if (!record.runs().isEmpty()) {
            ArrayNode runs = root.putArray("runs");
            for (RunState run : record.runs()) {
                RunState last = memo.complete() ? memo.runs().get(run.ref().run()) : null;
                memo.runs().put(run.ref().run(), run);
                runs.add(last == null ? mapper.valueToTree(run) : delta(mapper.valueToTree(run), mapper.valueToTree(last), "ref"));
            }
        }
        if (!record.removedRuns().isEmpty()) {
            root.set("removedRuns", mapper.valueToTree(record.removedRuns()));
            record.removedRuns().forEach(memo.runs()::remove);
        }
        if (record.usage() != null) {
            root.put("usageDay", record.usageDay().toString());
            root.set("usage", mapper.valueToTree(record.usage()));
        }
        if (!record.commands().isEmpty()) {
            ArrayNode commands = root.putArray("commands");
            for (Command command : record.commands()) {
                commands.add(command instanceof CallModel call ? callModel(call, memo) : mapper.valueToTree(command));
            }
        }
        if (!record.facts().isEmpty()) {
            ArrayNode facts = root.putArray("facts");
            for (Fact fact : record.facts()) {
                facts.add(fact instanceof UnknownFact unknown ? unknown.raw() : mapper.valueToTree(fact));
            }
        }
        shrink(root, memo);
        return mapper.writeValueAsString(root);
    }

    /** 解一行。{@code memo} 是 {@link RecordMemo#reader()}，按文件顺序收录过前面的事件行和结果行。 */
    public Decoded decode(String line, RecordMemo memo) {
        try {
            JsonNode root = mapper.readTree(line);
            expand(root, memo);
            long seq = root.required("result").asLong();
            SessionChange session = root.has("session") ? session(root.get("session"), memo) : null;
            List<RunState> runs = new ArrayList<>();
            for (JsonNode node : root.path("runs")) {
                RunState run = node.path("delta").asBoolean(false)
                        ? mapper.treeToValue(merge(node, memo.runs().get(mapper.treeToValue(node.required("ref"), RunRef.class).run()), "run"),
                                RunState.class)
                        : mapper.treeToValue(node, RunState.class);
                memo.runs().put(run.ref().run(), run);
                runs.add(run);
            }
            List<RunId> removed = root.has("removedRuns") ? mapper.convertValue(root.get("removedRuns"), RUN_IDS) : List.of();
            removed.forEach(memo.runs()::remove);
            LocalDate day = root.has("usageDay") ? LocalDate.parse(root.get("usageDay").asString()) : null;
            TokenUsage usage = root.has("usage") ? mapper.treeToValue(root.get("usage"), TokenUsage.class) : null;
            List<Command> commands = new ArrayList<>();
            Set<CommandId> mismatches = new HashSet<>();
            for (JsonNode command : root.path("commands")) {
                if ("CallModel".equals(command.path("type").asString())) {
                    CallModel call = callModel(command, memo);
                    if (!fingerprint(call.prompt()).equals(command.path("fingerprint").asString())) {
                        mismatches.add(call.id());
                    }
                    commands.add(call);
                } else {
                    commands.add(mapper.treeToValue(command, Command.class));
                }
            }
            List<Fact> facts = new ArrayList<>();
            for (JsonNode fact : root.path("facts")) {
                facts.add(fact(fact));
            }
            return new Decoded(new StepRecord(seq, session, runs, removed, day, usage, commands, facts), Set.copyOf(mismatches));
        } catch (JacksonException | IllegalArgumentException | IllegalStateException failure) {
            throw new IllegalArgumentException("corrupt result line: " + failure.getMessage(), failure);
        }
    }

    /** 请求的指纹：用 journal 和接口的同一个 mapper 编码后取 SHA-256，只覆盖 {@link Prompt}，不含请求头和凭据。 */
    public String fingerprint(Prompt prompt) {
        return RecordMemo.hash(new String(events.writeValueAsBytes(prompt), StandardCharsets.UTF_8));
    }

    private ObjectNode session(SessionChange change, RecordMemo memo) {
        ObjectNode node = mapper.createObjectNode();
        node.set("id", mapper.valueToTree(change.id()));
        node.set("principal", mapper.valueToTree(change.principal()));
        if (change.created()) {
            node.put("created", true);
        }
        if (!change.appended().isEmpty()) {
            node.set("appended", mapper.valueToTree(change.appended()));
            memo.messages().addAll(change.appended());
        }
        changed(node, "activeRun", change.activeRun());
        changed(node, "lastRun", change.lastRun());
        changed(node, "followUps", change.followUps());
        if (change.context() != null) {
            node.set("context", context(change.context().value(), memo));
        }
        changed(node, "compaction", change.compaction());
        changed(node, "anchor", change.anchor());
        changed(node, "omittedImages", change.omittedImages());
        return node;
    }

    private SessionChange session(JsonNode node, RecordMemo memo) {
        List<Message> appended = node.has("appended") ? mapper.convertValue(node.get("appended"), MESSAGES) : List.of();
        memo.messages().addAll(appended);
        return new SessionChange(mapper.treeToValue(node.required("id"), SessionId.class),
                mapper.treeToValue(node.required("principal"), Principal.class),
                node.path("created").asBoolean(false), appended,
                changed(node, "activeRun", RunId.class), changed(node, "lastRun", RunId.class),
                changedList(node, "followUps", MESSAGES),
                node.has("context") ? new SessionChange.Changed<>(context(node.get("context"), memo)) : null,
                changed(node, "compaction", Compaction.class), changed(node, "anchor", UsageAnchor.class),
                changedList(node, "omittedImages", STRINGS));
    }

    /** 上下文只在会话开始时带上 system 和全部工具，之后每次提问只换几个字段：知道上一份时只写变了的字段。 */
    private JsonNode context(SessionContext context, RecordMemo memo) {
        SessionContext last = memo.complete() ? memo.context() : null;
        memo.context(context);
        if (context == null) {
            return mapper.nullNode();
        }
        ObjectNode full = mapper.valueToTree(context);
        return last == null ? full : delta(full, mapper.valueToTree(last), null);
    }

    /** 只留和上一份不同的字段（没有的字段算 null），{@code keep} 字段总是留着，用来找到上一份。 */
    private ObjectNode delta(ObjectNode now, ObjectNode before, String keep) {
        ObjectNode delta = mapper.createObjectNode();
        delta.put("delta", true);
        Set<String> names = new LinkedHashSet<>(now.propertyNames());
        names.addAll(before.propertyNames());
        for (String name : names) {
            JsonNode value = now.has(name) ? now.get(name) : mapper.nullNode();
            if (name.equals(keep) || !value.equals(before.has(name) ? before.get(name) : mapper.nullNode())) {
                delta.set(name, value);
            }
        }
        return delta;
    }

    /** {@link #delta} 的反向。 */
    private ObjectNode merge(JsonNode delta, Object before, String what) {
        if (before == null) {
            throw new IllegalArgumentException(what + " change refers to an earlier " + what + " that is not in the file");
        }
        ObjectNode merged = mapper.valueToTree(before);
        for (Map.Entry<String, JsonNode> field : delta.properties()) {
            if (!field.getKey().equals("delta")) {
                if (field.getValue().isNull()) {
                    merged.remove(field.getKey());
                } else {
                    merged.set(field.getKey(), field.getValue());
                }
            }
        }
        return merged;
    }

    private SessionContext context(JsonNode node, RecordMemo memo) {
        if (node.isNull()) {
            memo.context(null);
            return null;
        }
        SessionContext context = node.path("delta").asBoolean(false)
                ? mapper.treeToValue(merge(node, memo.context(), "context"), SessionContext.class)
                : mapper.treeToValue(node, SessionContext.class);
        memo.context(context);
        return context;
    }

    /** 变了才写；变成 null 写 JSON null。 */
    private void changed(ObjectNode node, String name, SessionChange.Changed<?> change) {
        if (change != null) {
            node.set(name, change.value() == null ? mapper.nullNode() : mapper.valueToTree(change.value()));
        }
    }

    private <T> SessionChange.Changed<T> changed(JsonNode node, String name, Class<T> type) {
        if (!node.has(name)) {
            return null;
        }
        JsonNode value = node.get(name);
        return new SessionChange.Changed<>(value.isNull() ? null : mapper.treeToValue(value, type));
    }

    private <T> SessionChange.Changed<List<T>> changedList(JsonNode node, String name, TypeReference<List<T>> type) {
        if (!node.has(name)) {
            return null;
        }
        JsonNode value = node.get(name);
        return new SessionChange.Changed<>(value.isNull() ? null : mapper.convertValue(value, type));
    }

    /**
     * 模型调用：system 和工具和上一次相同时不写；消息写和上一次相同的前几条有多少（{@code keep}）和之后的（{@code add}）。
     * 上一次不知道时全写。
     */
    private ObjectNode callModel(CallModel call, RecordMemo memo) {
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
        node.put("fingerprint", fingerprint(prompt));
        memo.lastPrompt(prompt);
        return node;
    }

    private CallModel callModel(JsonNode node, RecordMemo memo) {
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
                messages, mapper.treeToValue(written.required("output"), Prompt.Output.class));
        memo.lastPrompt(prompt);
        List<String> omitted = node.has("omittedImages") ? mapper.convertValue(node.get("omittedImages"), STRINGS) : List.of();
        return new CallModel(mapper.treeToValue(node.required("id"), CommandId.class),
                mapper.treeToValue(node.required("run"), RunRef.class), prompt, omitted);
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

    /** 请求里新加的消息通常就是刚追加进历史的那几条：从后往前在最近的历史里找，找到写下标。 */
    private ArrayNode messages(List<Message> messages, RecordMemo memo) {
        List<Message> history = memo.complete() ? memo.messages() : List.of();
        ArrayNode out = mapper.createArrayNode();
        for (Message message : messages) {
            int index = recent(history, message);
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

    private Fact fact(JsonNode node) {
        String type = node.path("type").asString();
        if (FACTS.contains(type)) {
            return mapper.treeToValue(node, Fact.class);
        }
        RunRef run = node.has("run") ? mapper.treeToValue(node.get("run"), RunRef.class) : null;
        return new UnknownFact(type, run, node);
    }

    private static int commonPrefix(List<Message> a, List<Message> b) {
        int n = Math.min(a.size(), b.size());
        int i = 0;
        while (i < n && (a.get(i) == b.get(i) || a.get(i).equals(b.get(i)))) {
            i++;
        }
        return i;
    }

    /** 按写出的顺序走一遍：见过的长字符串换成引用，第一次见的记下；字面以标记开头的转义。 */
    private static void shrink(JsonNode node, RecordMemo memo) {
        if (node instanceof ObjectNode object) {
            for (Map.Entry<String, JsonNode> field : object.properties()) {
                JsonNode value = field.getValue();
                if (value.isString()) {
                    field.setValue(object.stringNode(shrunk(value.stringValue(), memo)));
                } else {
                    shrink(value, memo);
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                JsonNode value = array.get(i);
                if (value.isString()) {
                    array.set(i, array.stringNode(shrunk(value.stringValue(), memo)));
                } else {
                    shrink(value, memo);
                }
            }
        }
    }

    private static String shrunk(String text, RecordMemo memo) {
        if (RecordMemo.isLong(text)) {
            String hash = RecordMemo.hash(text);
            if (memo.knows(hash)) {
                return REF + hash;
            }
            memo.remember(text);
        }
        return !text.isEmpty() && text.charAt(0) == MARK ? MARK + text : text;
    }

    /** {@link #shrink} 的反向：顺序相同，所以同一行里先出现的长字符串也能被后面的引用。 */
    private static void expand(JsonNode node, RecordMemo memo) {
        if (node instanceof ObjectNode object) {
            for (Map.Entry<String, JsonNode> field : object.properties()) {
                JsonNode value = field.getValue();
                if (value.isString()) {
                    field.setValue(object.stringNode(expanded(value.stringValue(), memo)));
                } else {
                    expand(value, memo);
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                JsonNode value = array.get(i);
                if (value.isString()) {
                    array.set(i, array.stringNode(expanded(value.stringValue(), memo)));
                } else {
                    expand(value, memo);
                }
            }
        }
    }

    private static String expanded(String text, RecordMemo memo) {
        if (!text.isEmpty() && text.charAt(0) == MARK) {
            if (text.length() == REF.length() + 64 && text.startsWith(REF)) {
                String found = memo.text(text.substring(REF.length()));
                if (found == null) {
                    throw new IllegalArgumentException("reference to a text that is not earlier in the file");
                }
                return found;
            }
            if (text.length() < 2 || text.charAt(1) != MARK) {
                throw new IllegalArgumentException("unknown escape in a result line");
            }
            text = text.substring(1);
        }
        memo.remember(text);
        return text;
    }
}
