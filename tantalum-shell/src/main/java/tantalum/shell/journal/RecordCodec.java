package tantalum.shell.journal;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
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
import tantalum.value.decision.StepFailed;
import tantalum.value.decision.SessionChange;
import tantalum.value.decision.StepRecord;
import tantalum.value.id.CommandId;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.model.Prompt;
import tantalum.value.model.TokenUsage;
import tantalum.value.state.Awaiting;
import tantalum.value.state.AwaitingAnswer;
import tantalum.value.state.Compacting;
import tantalum.value.state.Deciding;
import tantalum.value.state.Ended;
import tantalum.value.state.RunPhase;
import tantalum.value.state.RunState;
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
 * 结果行与 JSON 互转。一行是 {@code {"result": 序号, ...}}，没变的部分不写。这里只做编排：
 * 会话的变化在 {@link SessionDelta}，运行的变化在 {@link RunDelta}，模型调用在 {@link RequestDelta}，
 * 重复的内容块和长字符串在 {@link TextSlices}，重复的运行引用在 {@link RunRefs}。
 * 块的哈希取它的 JSON：浮点数按 {@code BigDecimal} 读，所以读出来再写一遍和原来逐字节相同。
 */
public final class RecordCodec {

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
        @JsonSubTypes.Type(value = QuestionSettled.class, name = "QuestionSettled"),
        @JsonSubTypes.Type(value = StepFailed.class, name = "StepFailed")
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

    /** 解出来的一行：结果，以及每次模型调用当时记下的请求指纹。核对要把整个请求再编码一遍，所以只在打开请求详情时做。 */
    public record Decoded(StepRecord record, Map<CommandId, String> fingerprints) {}

    private final JsonMapper mapper;
    private final SessionDelta sessions;
    private final RunDelta runs;
    private final RequestDelta requests;
    private final TextSlices slices;
    private final RunRefs runRefs;

    public RecordCodec(EventCodec codec) {
        this.mapper = codec.mapper().rebuild()
                .addMixIn(Command.class, CommandMixin.class)
                .addMixIn(Fact.class, FactMixin.class)
                .addMixIn(RunPhase.class, PhaseMixin.class)
                .addMixIn(Compacting.Cause.class, CauseMixin.class)
                .addMixIn(Awaiting.class, SortedCommandsMixin.class)
                .addMixIn(AbortCommands.class, SortedCommandsMixin.class)
                // 状态里出现不认识的字段说明是更新的版本写的，不能当作没看见
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .build();
        Deltas deltas = new Deltas(mapper);
        this.sessions = new SessionDelta(mapper, deltas);
        this.runs = new RunDelta(mapper, deltas);
        this.requests = new RequestDelta(mapper, codec.mapper());
        this.slices = new TextSlices(mapper);
        this.runRefs = new RunRefs(mapper);
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
            root.set("session", sessions.encode(record.session(), record.seq(), memo));
        }
        if (!record.runs().isEmpty()) {
            ArrayNode written = root.putArray("runs");
            for (RunState run : record.runs()) {
                RunState last = memo.complete() ? memo.runs().get(run.ref().run()) : null;
                memo.runs().put(run.ref().run(), run);
                written.add(last == null ? mapper.valueToTree(run) : runs.encode(run, last));
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
                commands.add(command instanceof CallModel call ? requests.encode(call, memo) : mapper.valueToTree(command));
            }
        }
        if (!record.facts().isEmpty()) {
            ArrayNode facts = root.putArray("facts");
            for (Fact fact : record.facts()) {
                // 副本：shrink 和 sameRun 会改写树，不能动事实自己的节点
                facts.add(fact instanceof UnknownFact unknown ? unknown.raw().deepCopy() : mapper.valueToTree(fact));
            }
        }
        slices.shrink(root, memo);
        runRefs.collapse(root, memo);
        memo.answered(record.seq());
        return root;
    }

    /**
     * 解第 {@code seq} 步的结果。{@code root} 会被改写（解开引用）。{@code memo} 是 {@link RecordMemo#reader()}，
     * 按文件顺序收录过前面的行和这一行的事件。
     */
    public Decoded decode(JsonNode root, long seq, RecordMemo memo) {
        try {
            runRefs.restore(root, memo);
            slices.expand(root, memo);
            SessionChange session = root.has("session") ? sessions.decode(root.get("session"), seq, memo) : null;
            List<RunState> decodedRuns = new ArrayList<>();
            for (JsonNode node : root.path("runs")) {
                RunState run = node.path("delta").asBoolean(false)
                        ? runs.merge((ObjectNode) node, memo.runs().get(mapper.treeToValue(node.required("ref"), RunRef.class).run()))
                        : mapper.treeToValue(node, RunState.class);
                memo.runs().put(run.ref().run(), run);
                decodedRuns.add(run);
            }
            List<RunId> removed = root.has("removedRuns") ? mapper.convertValue(root.get("removedRuns"), RUN_IDS) : List.of();
            removed.forEach(memo.runs()::remove);
            LocalDate day = root.has("usageDay") ? LocalDate.parse(root.get("usageDay").asString()) : null;
            TokenUsage usage = root.has("usage") ? mapper.treeToValue(root.get("usage"), TokenUsage.class) : null;
            List<Command> commands = new ArrayList<>();
            Map<CommandId, String> fingerprints = new HashMap<>();
            for (JsonNode command : root.path("commands")) {
                if ("CallModel".equals(command.path("type").asString())) {
                    CallModel call = requests.decode(command, memo);
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
            return new Decoded(new StepRecord(seq, session, decodedRuns, removed, day, usage, commands, facts), fingerprints);
        } catch (RuntimeException failure) {
            // 日期、强转、空值……任何解不开的都算损坏的行，调用方按行号报
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

    /** 请求的指纹：用 journal 和接口的同一个 mapper 编码后取 SHA-256，只覆盖 {@link Prompt}，不含请求头和凭据。 */
    public String fingerprint(Prompt prompt) {
        return requests.fingerprint(prompt);
    }

    private Fact fact(JsonNode node) {
        String type = node.path("type").asString();
        if (FACTS.contains(type)) {
            return mapper.treeToValue(node, Fact.class);
        }
        RunRef run = node.has("run") ? mapper.treeToValue(node.get("run"), RunRef.class) : null;
        return new UnknownFact(type, run, node);
    }
}
