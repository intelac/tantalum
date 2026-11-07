package tantalum.shell.journal;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
import tantalum.value.decision.QuestionAsked;
import tantalum.value.decision.QuestionSettled;
import tantalum.value.decision.SessionChange;
import tantalum.value.decision.StepRecord;
import tantalum.value.id.CommandId;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.Message;
import tantalum.value.model.ModelChoice;
import tantalum.value.model.Prompt;
import tantalum.value.model.Role;
import tantalum.value.model.TokenUsage;
import tantalum.value.model.ToolSpec;
import tantalum.value.state.Awaiting;
import tantalum.value.state.AwaitingAnswer;
import tantalum.value.state.Compacting;
import tantalum.value.state.Compaction;
import tantalum.value.state.Deciding;
import tantalum.value.state.Ended;
import tantalum.value.state.RunPhase;
import tantalum.value.state.RunState;
import tantalum.value.state.SessionContext;
import tantalum.value.state.ToolRound;
import tantalum.value.state.ToolSlot;
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
 * 省掉重复：结果行里的内容块（文字、思考、工具调用和结果……）在文件前面出现过就整块写成引用 {@code "\u0000@"+SHA-256}，
 * 长字符串写成 {@code "\u0000#"+SHA-256}（字面以 {@code \u0000} 开头的字符串多加一个 {@code \u0000}）；模型调用的请求只写
 * 相对这个会话上一次请求的变化，另记原样请求的指纹。引用只指向更早的内容，按文件顺序读一遍就能全部解开。
 * 块的哈希取它的 JSON：浮点数按 {@code BigDecimal} 读，所以读出来再写一遍和原来逐字节相同。
 */
public final class RecordCodec {

    private static final char MARK = '\u0000';
    /** 请求里的消息往回找多少条历史。 */
    private static final int RECENT = 64;
    private static final String REF = MARK + "#";
    private static final String BLOCK_REF = MARK + "@";
    /** 旧字符串掐头去尾：{@code "\u0000$"+哈希+":"+开头长度+":"+结尾长度+":"+中间}（压缩摘要调用里裁剪过的工具结果）。 */
    private static final String SLICE = MARK + "$";
    /** 一行里和顶层 {@code "run"} 相同的运行引用：同一步的运行、命令、判断几乎都指向同一个运行。 */
    private static final String SAME_RUN = MARK + "=";
    /** 掐头去尾至少保留这么多字符才值得写成切片。 */
    private static final int MIN_SLICE_KEPT = 64;
    /** 内容块的类型名（{@code EventCodec} 里 {@code ContentBlock} 的 {@code kind}）。 */
    private static final Set<String> BLOCK_KINDS = Set.of("text", "reasoning", "toolCall", "toolResult", "attachment", "toolChange",
            "modelChange");
    /** 序列化后至少这么长的块才写成引用。 */
    private static final int MIN_BLOCK_BYTES = 96;
    private static final TypeReference<List<Message>> MESSAGES = new TypeReference<>() { };
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() { };
    private static final TypeReference<Map<String, Json>> NOTES = new TypeReference<>() { };
    private static final TypeReference<List<RunId>> RUN_IDS = new TypeReference<>() { };
    private static final Set<String> FACTS = Set.of("PhaseChanged", "ContextCompacted", "CompactionFailed", "HookDecided",
            "HookReported", "ImagesOmitted", "QuestionAsked", "QuestionSettled");

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
        @JsonSubTypes.Type(value = ImagesOmitted.class, name = "ImagesOmitted"),
        @JsonSubTypes.Type(value = QuestionAsked.class, name = "QuestionAsked"),
        @JsonSubTypes.Type(value = QuestionSettled.class, name = "QuestionSettled")
    })
    private interface FactMixin {}

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = Deciding.class, name = "Deciding"),
        @JsonSubTypes.Type(value = Awaiting.class, name = "Awaiting"),
        @JsonSubTypes.Type(value = AwaitingAnswer.class, name = "AwaitingAnswer"),
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

    /** 解出来的一行：结果，以及每次模型调用当时记下的请求指纹。核对要把整个请求再编码一遍，所以只在打开请求详情时做（需求 4.2）。 */
    public record Decoded(StepRecord record, Map<CommandId, String> fingerprints) {}

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
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .build();
    }

    /** 单独编码一步的结果：{@code {"result": 序号, ...}}。会话文件里结果是一行的 {@code result} 字段，见 {@link #encodeTree}。 */
    public String encode(StepRecord record, RecordMemo memo) {
        ObjectNode root = mapper.createObjectNode();
        root.put("result", record.seq());
        root.setAll(encodeTree(record, memo));
        return mapper.writeValueAsString(root);
    }

    /** {@link #encode} 的反向。 */
    public Decoded decode(String line, RecordMemo memo) {
        JsonNode root;
        try {
            root = mapper.readTree(line);
        } catch (JacksonException failure) {
            throw new IllegalArgumentException("corrupt result line: " + failure.getMessage(), failure);
        }
        if (!(root instanceof ObjectNode object) || !object.path("result").canConvertToLong()) {
            throw new IllegalArgumentException("corrupt result line: no result seq");
        }
        long seq = object.remove("result").asLong();
        return decode(object, seq, memo);
    }

    /** 解析一行会话文件用的 mapper：浮点数按 {@code BigDecimal} 读，事件和结果读出来都和文本逐字节相同。 */
    public JsonNode parse(String line) {
        return mapper.readTree(line);
    }

    /** 编码一步的结果（不含序号，序号在这一行的事件里），并把写过的长字符串和请求记进 {@code memo}。 */
    public ObjectNode encodeTree(StepRecord record, RecordMemo memo) {
        ObjectNode root = mapper.createObjectNode();
        if (record.session() != null) {
            root.set("session", session(record.session(), record.seq(), memo));
        }
        if (!record.runs().isEmpty()) {
            ArrayNode runs = root.putArray("runs");
            for (RunState run : record.runs()) {
                RunState last = memo.complete() ? memo.runs().get(run.ref().run()) : null;
                memo.runs().put(run.ref().run(), run);
                runs.add(last == null ? mapper.valueToTree(run) : runDelta(run, last));
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
        sameRun(root, memo);
        memo.answered(record.seq());
        return root;
    }

    /**
     * 解第 {@code seq} 步的结果。{@code root} 会被改写（解开引用）。{@code memo} 是 {@link RecordMemo#reader()}，
     * 按文件顺序收录过前面的行和这一行的事件。
     */
    public Decoded decode(JsonNode root, long seq, RecordMemo memo) {
        try {
            restoreRun(root, memo);
            expand(root, memo);
            SessionChange session = root.has("session") ? session(root.get("session"), seq, memo) : null;
            List<RunState> runs = new ArrayList<>();
            for (JsonNode node : root.path("runs")) {
                RunState run = node.path("delta").asBoolean(false)
                        ? runMerge((ObjectNode) node, memo.runs().get(mapper.treeToValue(node.required("ref"), RunRef.class).run()))
                        : mapper.treeToValue(node, RunState.class);
                memo.runs().put(run.ref().run(), run);
                runs.add(run);
            }
            List<RunId> removed = root.has("removedRuns") ? mapper.convertValue(root.get("removedRuns"), RUN_IDS) : List.of();
            removed.forEach(memo.runs()::remove);
            LocalDate day = root.has("usageDay") ? LocalDate.parse(root.get("usageDay").asString()) : null;
            TokenUsage usage = root.has("usage") ? mapper.treeToValue(root.get("usage"), TokenUsage.class) : null;
            List<Command> commands = new ArrayList<>();
            Map<CommandId, String> fingerprints = new HashMap<>();
            for (JsonNode command : root.path("commands")) {
                if ("CallModel".equals(command.path("type").asString())) {
                    CallModel call = callModel(command, memo);
                    fingerprints.put(call.id(), command.path("fingerprint").asString());
                    commands.add(call);
                } else {
                    commands.add(mapper.treeToValue(command, Command.class));
                }
            }
            List<Fact> facts = new ArrayList<>();
            for (JsonNode fact : root.path("facts")) {
                facts.add(fact(fact));
            }
            memo.answered(seq);
            return new Decoded(new StepRecord(seq, session, runs, removed, day, usage, commands, facts), fingerprints);
        } catch (JacksonException | IllegalArgumentException | IllegalStateException failure) {
            throw new IllegalArgumentException("corrupt result line: " + failure.getMessage(), failure);
        }
    }

    /**
     * 收录一行事件 payload 里的长字符串。事件里的内容块不收录：读文件时为每个事件的每个块算哈希太贵，
     * 而事件的内容块几乎只出现在这一步追加的助手消息里，那里写“来自事件”（{@code fromEvent}）。
     */
    public void see(String eventPayload, RecordMemo memo) {
        memo.see(mapper.readTree(eventPayload));
    }

    private static boolean isBlock(JsonNode node) {
        return node instanceof ObjectNode && node.path("kind").isString() && BLOCK_KINDS.contains(node.get("kind").asString());
    }

    /** 一个块的哈希；太小不值得引用时为 null。 */
    private String blockHash(JsonNode node) {
        byte[] json = mapper.writeValueAsBytes(node);
        return json.length < MIN_BLOCK_BYTES ? null : RecordMemo.hash(new String(json, StandardCharsets.UTF_8));
    }

    /** 请求的指纹：用 journal 和接口的同一个 mapper 编码后取 SHA-256，只覆盖 {@link Prompt}，不含请求头和凭据。 */
    public String fingerprint(Prompt prompt) {
        return RecordMemo.hash(new String(events.writeValueAsBytes(prompt), StandardCharsets.UTF_8));
    }

    private ObjectNode session(SessionChange change, long seq, RecordMemo memo) {
        ObjectNode node = mapper.createObjectNode();
        // 文件头已经写明属于哪个会话：是它自己时不再写
        if (!change.id().equals(memo.session()) || !change.principal().equals(memo.principal())) {
            node.set("id", mapper.valueToTree(change.id()));
            node.set("principal", mapper.valueToTree(change.principal()));
        }
        if (change.created()) {
            node.put("created", true);
        }
        if (!change.appended().isEmpty()) {
            List<ContentBlock> reply = memo.reply(seq);
            ArrayNode appended = node.putArray("appended");
            for (Message message : change.appended()) {
                ObjectNode written = mapper.valueToTree(message);
                if (reply != null && message.role() == Role.ASSISTANT && message.content().equals(reply)) {
                    written.remove("content");
                    written.put("fromEvent", true);
                }
                appended.add(written);
                memo.appended(message);
            }
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
        changed(node, "toolsSeen", change.toolsSeen());
        changed(node, "notes", change.notes());
        changed(node, "model", change.model());
        changed(node, "followUpModel", change.followUpModel());
        return node;
    }

    private SessionChange session(JsonNode node, long seq, RecordMemo memo) {
        List<Message> appended = new ArrayList<>();
        for (JsonNode item : node.path("appended")) {
            Message message;
            if (item.path("fromEvent").asBoolean(false)) {
                List<ContentBlock> reply = memo.reply(seq);
                if (reply == null) {
                    throw new IllegalArgumentException("message from an event that is not a model reply of this step");
                }
                message = new Message(Role.valueOf(item.required("role").asString()), reply, item.path("interrupted").asBoolean(false));
            } else {
                message = mapper.treeToValue(item, Message.class);
            }
            appended.add(message);
            memo.appended(message);
        }
        if (!node.has("id") && memo.session() == null) {
            throw new IllegalArgumentException("a session change without its id needs the file's session");
        }
        return new SessionChange(node.has("id") ? mapper.treeToValue(node.get("id"), SessionId.class) : memo.session(),
                node.has("principal") ? mapper.treeToValue(node.get("principal"), Principal.class) : memo.principal(),
                node.path("created").asBoolean(false), appended,
                changed(node, "activeRun", RunId.class), changed(node, "lastRun", RunId.class),
                changedList(node, "followUps", MESSAGES),
                node.has("context") ? new SessionChange.Changed<>(context(node.get("context"), memo)) : null,
                changed(node, "compaction", Compaction.class), changed(node, "anchor", UsageAnchor.class),
                changedList(node, "omittedImages", STRINGS), changedList(node, "toolsSeen", STRINGS), notes(node),
                changed(node, "model", ModelChoice.class), changed(node, "followUpModel", ModelChoice.class));
    }

    /** 插件工作记在会话里的东西：工作类型 → 对象。 */
    private SessionChange.Changed<Map<String, Json.Obj>> notes(JsonNode node) {
        if (!node.has("notes")) {
            return null;
        }
        JsonNode value = node.get("notes");
        if (value.isNull()) {
            return new SessionChange.Changed<>(null);
        }
        Map<String, Json.Obj> notes = new LinkedHashMap<>();
        mapper.convertValue(value, NOTES).forEach((type, note) -> {
            if (!(note instanceof Json.Obj obj)) {
                throw new IllegalArgumentException("note of " + type + " is not an object");
            }
            notes.put(type, obj);
        });
        return new SessionChange.Changed<>(notes);
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

    /**
     * 运行的变化。工具轮每完成一个调用就变一次，其中只有一格变了：两边都有工具轮时不写整个 {@code tools}，
     * 写 {@code toolSlots}（变了的格，按下标）和 {@code toolCount}、{@code toolsChecked}。这些名字不是 {@code RunState} 的字段，不会混淆。
     */
    private ObjectNode runDelta(RunState now, RunState before) {
        ObjectNode delta = delta(mapper.valueToTree(now), mapper.valueToTree(before), "ref");
        if (delta.has("tools") && now.tools() != null && before.tools() != null
                && now.tools().slots().size() >= before.tools().slots().size()) {
            delta.remove("tools");
            ObjectNode slots = delta.putObject("toolSlots");
            for (int i = 0; i < now.tools().slots().size(); i++) {
                if (i >= before.tools().slots().size() || !now.tools().slots().get(i).equals(before.tools().slots().get(i))) {
                    slots.set(Integer.toString(i), mapper.valueToTree(now.tools().slots().get(i)));
                }
            }
            delta.put("toolCount", now.tools().slots().size());
            if (now.tools().checked() != before.tools().checked()) {
                delta.put("toolsChecked", now.tools().checked());
            }
        }
        return delta;
    }

    private RunState runMerge(ObjectNode delta, RunState before) {
        JsonNode slots = delta.remove("toolSlots");
        JsonNode count = delta.remove("toolCount");
        JsonNode checked = delta.remove("toolsChecked");
        RunState merged = mapper.treeToValue(merge(delta, before, "run"), RunState.class);
        if (slots == null) {
            return merged;
        }
        if (before.tools() == null || count == null) {
            throw new IllegalArgumentException("tool slot changes refer to a tool round that is not in the file");
        }
        List<ToolSlot> round = new ArrayList<>(before.tools().slots());
        for (int i = 0; i < count.asInt(); i++) {
            JsonNode slot = slots.get(Integer.toString(i));
            if (slot != null) {
                ToolSlot value = mapper.treeToValue(slot, ToolSlot.class);
                if (i < round.size()) {
                    round.set(i, value);
                } else {
                    round.add(value);
                }
            } else if (i >= round.size()) {
                throw new IllegalArgumentException("tool slot " + i + " is neither earlier in the file nor in this change");
            }
        }
        return merged.withTools(new ToolRound(round, checked == null ? before.tools().checked() : checked.asInt()));
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
     * 模型选择和上一次相同时不写，变成没有时写 null（model-picker M13）。上一次不知道时全写，选择为空时不写。
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
        ModelChoice before = last == null ? null : last.model();
        if (!java.util.Objects.equals(before, prompt.model())) {
            written.set("model", prompt.model() == null ? mapper.nullNode() : mapper.valueToTree(prompt.model()));
        }
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

    private static String unslice(String text, RecordMemo memo) {
        String[] parts = text.substring(SLICE.length()).split(":", 4);
        String old = parts.length == 4 ? memo.text(parts[0]) : null;
        if (old == null) {
            throw new IllegalArgumentException("slice of a text that is not earlier in the file");
        }
        int head = Integer.parseInt(parts[1]);
        int tail = Integer.parseInt(parts[2]);
        if (head < 0 || tail < 0 || head + tail > old.length()) {
            throw new IllegalArgumentException("slice outside its text");
        }
        return old.substring(0, head) + parts[3] + old.substring(old.length() - tail);
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

    /** 按写出的顺序走一遍：见过的内容块和长字符串换成引用，第一次见的记下；字面以标记开头的字符串转义。 */
    private void shrink(JsonNode node, RecordMemo memo) {
        if (node instanceof ObjectNode object) {
            for (Map.Entry<String, JsonNode> field : object.properties()) {
                field.setValue(shrunkChild(field.getValue(), object, memo));
            }
        } else if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                array.set(i, shrunkChild(array.get(i), array, memo));
            }
        }
    }

    private JsonNode shrunkChild(JsonNode value, tools.jackson.databind.node.ContainerNode<?> parent, RecordMemo memo) {
        if (value.isString()) {
            return parent.stringNode(shrunk(value.stringValue(), memo));
        }
        if (isBlock(value)) {
            String hash = blockHash(value);
            if (hash != null && memo.knowsBlock(hash)) {
                return parent.stringNode(BLOCK_REF + hash);
            }
            if (hash != null) {
                memo.rememberBlock(hash, value);
            }
        }
        shrink(value, memo);
        return value;
    }

    private static String shrunk(String text, RecordMemo memo) {
        if (RecordMemo.isLong(text)) {
            String hash = RecordMemo.hash(text);
            if (memo.knows(hash)) {
                return REF + hash;
            }
            String slice = slice(text, memo);
            memo.remember(text);
            if (slice != null) {
                return slice;
            }
        }
        return !text.isEmpty() && text.charAt(0) == MARK ? MARK + text : text;
    }

    /** 新字符串是某个旧字符串的开头加结尾、中间换了一段时，写成切片；找不到时为 null。 */
    private static String slice(String text, RecordMemo memo) {
        String best = null;
        int bestKept = MIN_SLICE_KEPT;
        for (String hash : memo.sameStart(text)) {
            String old = memo.text(hash);
            int head = commonPrefix(old, text);
            int tail = commonSuffix(old, text, Math.min(old.length(), text.length()) - head);
            if (head + tail > bestKept) {
                bestKept = head + tail;
                best = SLICE + hash + ":" + head + ":" + tail + ":" + text.substring(head, text.length() - tail);
            }
        }
        return best != null && best.length() < text.length() ? best : null;
    }

    private static int commonPrefix(String a, String b) {
        int n = Math.min(a.length(), b.length());
        int i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        // 不在代理对中间切开
        return i > 0 && Character.isHighSurrogate(a.charAt(i - 1)) ? i - 1 : i;
    }

    private static int commonSuffix(String a, String b, int limit) {
        int i = 0;
        while (i < limit && a.charAt(a.length() - 1 - i) == b.charAt(b.length() - 1 - i)) {
            i++;
        }
        return i > 0 && Character.isLowSurrogate(a.charAt(a.length() - i)) ? i - 1 : i;
    }

    /**
     * 顶层记一份最先出现的运行引用，其余相同的写成 {@code "\u0000="}；运行属于这个文件的会话时顶层只写运行 ID。
     * 在 {@link #shrink} 之后做，所以这个标记不会被转义。
     */
    private void sameRun(ObjectNode root, RecordMemo memo) {
        JsonNode run = firstRun(root);
        if (run == null) {
            return;
        }
        JsonNode first = run.deepCopy();
        replaceRun(root, first);
        RunRef ref = mapper.treeToValue(first, RunRef.class);
        boolean own = ref.principal().equals(memo.principal()) && ref.session().equals(memo.session());
        root.set("run", own ? root.stringNode(ref.run().value()) : first);
    }

    private static JsonNode firstRun(JsonNode node) {
        if (isRunRef(node)) {
            return node;
        }
        for (JsonNode child : node) {
            JsonNode found = firstRun(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static boolean isRunRef(JsonNode node) {
        return node instanceof ObjectNode && node.size() == 3 && node.has("principal") && node.has("session") && node.has("run");
    }

    private static void replaceRun(JsonNode node, JsonNode run) {
        if (node instanceof ObjectNode object) {
            for (Map.Entry<String, JsonNode> field : object.properties()) {
                if (field.getValue().equals(run)) {
                    field.setValue(object.stringNode(SAME_RUN));
                } else {
                    replaceRun(field.getValue(), run);
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                if (array.get(i).equals(run)) {
                    array.set(i, array.stringNode(SAME_RUN));
                } else {
                    replaceRun(array.get(i), run);
                }
            }
        }
    }

    /** {@link #sameRun} 的反向，在解开其他引用之前做。 */
    private void restoreRun(JsonNode root, RecordMemo memo) {
        if (!(root instanceof ObjectNode object) || !object.has("run")) {
            return;
        }
        JsonNode run = object.remove("run");
        if (run.isString()) {
            if (memo.principal() == null) {
                throw new IllegalArgumentException("a short run reference needs the file's session");
            }
            run = mapper.valueToTree(new RunRef(memo.principal(), memo.session(), new RunId(run.asString())));
        }
        restoreRun(object, run);
    }

    private static void restoreRun(JsonNode node, JsonNode run) {
        if (node instanceof ObjectNode object) {
            for (Map.Entry<String, JsonNode> field : object.properties()) {
                if (field.getValue().isString() && SAME_RUN.equals(field.getValue().stringValue())) {
                    field.setValue(run.deepCopy());
                } else {
                    restoreRun(field.getValue(), run);
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                if (array.get(i).isString() && SAME_RUN.equals(array.get(i).stringValue())) {
                    array.set(i, run.deepCopy());
                } else {
                    restoreRun(array.get(i), run);
                }
            }
        }
    }

    /** {@link #shrink} 的反向：顺序相同，所以同一行里先出现的块和长字符串也能被后面的引用。 */
    private void expand(JsonNode node, RecordMemo memo) {
        if (node instanceof ObjectNode object) {
            for (Map.Entry<String, JsonNode> field : object.properties()) {
                field.setValue(expandedChild(field.getValue(), object, memo));
            }
        } else if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                array.set(i, expandedChild(array.get(i), array, memo));
            }
        }
    }

    private JsonNode expandedChild(JsonNode value, tools.jackson.databind.node.ContainerNode<?> parent, RecordMemo memo) {
        if (value.isString()) {
            String text = value.stringValue();
            if (text.length() == BLOCK_REF.length() + RecordMemo.HASH_HEX && text.startsWith(BLOCK_REF)) {
                JsonNode block = memo.block(text.substring(BLOCK_REF.length()));
                if (block == null) {
                    throw new IllegalArgumentException("reference to a block that is not earlier in the file");
                }
                return block.deepCopy();
            }
            return parent.stringNode(expanded(text, memo));
        }
        expand(value, memo);
        if (isBlock(value)) {
            String hash = blockHash(value);
            if (hash != null) {
                memo.rememberBlock(hash, value);
            }
        }
        return value;
    }

    private static String expanded(String text, RecordMemo memo) {
        if (!text.isEmpty() && text.charAt(0) == MARK) {
            if (text.length() == REF.length() + RecordMemo.HASH_HEX && text.startsWith(REF)) {
                String found = memo.text(text.substring(REF.length()));
                if (found == null) {
                    throw new IllegalArgumentException("reference to a text that is not earlier in the file");
                }
                return found;
            }
            if (text.startsWith(SLICE)) {
                String sliced = unslice(text, memo);
                memo.remember(sliced);
                return sliced;
            }
            if (text.length() < 2 || text.charAt(1) != MARK) {
                throw new IllegalArgumentException("unknown escape in a result line");
            }
            // 字面以标记开头的字符串：去掉转义用的那个标记
            text = text.substring(1);
        }
        memo.remember(text);
        return text;
    }
}
