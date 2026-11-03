package tantalum.shell.conversation;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tantalum.kernel.Kernel;
import tantalum.kernel.Limits;
import tantalum.kernel.hook.HookChain;
import tantalum.kernel.hook.KernelHook;
import tantalum.kernel.step.BuiltinSteps;
import tantalum.kernel.table.TransitionTable;
import tantalum.shell.conversation.TranscriptItem.RunStatus;
import tantalum.shell.conversation.TranscriptItem.ToolStep;
import tantalum.value.command.CallModel;
import tantalum.value.command.Command;
import tantalum.value.command.RunTool;
import tantalum.value.decision.Decision;
import tantalum.value.event.ContextAssembled;
import tantalum.value.event.Event;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.ModelReplied;
import tantalum.value.event.ToolFinished;
import tantalum.value.event.UserPrompt;
import tantalum.value.id.CommandId;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;
import tantalum.value.id.SubmissionId;
import tantalum.value.json.Json;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.ReasoningBlock;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tantalum.value.model.TokenUsage;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.state.CompactionSettings;
import tantalum.value.state.KernelState;
import tantalum.value.state.OwnerRef;

/** 把事件喂给真实内核，再把 {@code (envelope, decision)} 交给 {@link Transcript}。每个事件时间前进 1 秒。 */
final class TranscriptFixture {

    static final Principal USER = new Principal("u");
    static final SessionId SESSION = new SessionId("s");
    static final Instant T0 = Instant.parse("2026-10-31T00:00:00Z");

    record Change(long seq, int index, TranscriptItem item) {}

    final List<Change> changes = new ArrayList<>();
    final Transcript transcript = new Transcript();
    final List<EventEnvelope> envelopes = new ArrayList<>();
    final List<Decision> decisions = new ArrayList<>();

    private final Kernel kernel;
    private KernelState state = KernelState.empty();
    private long seq;
    private Instant now = T0;
    private int submissions;

    TranscriptFixture() {
        this(true, List.of());
    }

    TranscriptFixture(boolean concurrencySafe, List<KernelHook> hooks) {
        TransitionTable table = new TransitionTable();
        BuiltinSteps.register(table);
        kernel = new Kernel(table, new HookChain(hooks), (tool, args) -> concurrencySafe, Limits.DEFAULT);
    }

    Decision feed(RunId run, Event event) {
        EventEnvelope envelope = new EventEnvelope(seq++, now, USER, SESSION, run, event);
        now = now.plusSeconds(1);
        Decision decision = kernel.step(state, envelope);
        state = decision.newState();
        envelopes.add(envelope);
        decisions.add(decision);
        int index = 0;
        for (TranscriptItem item : transcript.apply(envelope, decision)) {
            changes.add(new Change(envelope.seq(), index++, item));
        }
        return decision;
    }

    void tick(Duration delta) {
        now = now.plus(delta);
    }

    /** 下一个事件的时间。 */
    Instant now() {
        return now;
    }

    long lastSeq() {
        return seq - 1;
    }

    KernelState state() {
        return state;
    }

    List<TranscriptItem> items() {
        return transcript.items(SESSION);
    }

    <T extends TranscriptItem> List<T> items(Class<T> type) {
        return items().stream().filter(type::isInstance).map(type::cast).toList();
    }

    RunStatus status(RunId run) {
        return items(RunStatus.class).stream().filter(status -> status.run().equals(run)).findFirst().orElseThrow();
    }

    ToolStep step(String callId) {
        return items(ToolStep.class).stream().filter(step -> step.call().id().equals(callId)).findFirst().orElseThrow();
    }

    /** 开始一次运行并走完上下文组装，返回运行 ID。 */
    RunId start(String text) {
        return start(text, null);
    }

    /** 同上，但带压缩设置（会话第一次组装时冻结）。 */
    RunId start(String text, CompactionSettings settings) {
        feed(null, prompt(text));
        RunId run = RunId.of(SESSION, lastSeq());
        CommandId assemble = lastCommands().getFirst().id();
        feed(run, new ContextAssembled(assemble, "sys", List.of(), settings, List.of()));
        return run;
    }

    UserPrompt prompt(String text) {
        int n = submissions++;
        return new UserPrompt(new SubmissionId("sub-" + n), new RequestId("req-" + n), new OwnerRef.OfSession(SESSION), text);
    }

    List<Command> lastCommands() {
        return decisions.getLast().commands();
    }

    /** 最近一次 {@code CallModel} 的命令 ID，不一定在最后一个决定里。 */
    CommandId callModel() {
        return decisions.reversed().stream().flatMap(decision -> decision.commands().stream())
                .filter(CallModel.class::isInstance).map(Command::id).findFirst().orElseThrow();
    }

    List<RunTool> runTools() {
        return lastCommands().stream().filter(RunTool.class::isInstance).map(RunTool.class::cast).toList();
    }

    /** 模型回复，命令 ID 取最近一次 {@code CallModel}。 */
    ModelReplied reply(ContentBlock... content) {
        return reply(null, content);
    }

    ModelReplied reply(TokenUsage usage, ContentBlock... content) {
        boolean tools = List.of(content).stream().anyMatch(ToolCallBlock.class::isInstance);
        return new ModelReplied(callModel(), List.of(content), tools ? StopReason.TOOL_USE : StopReason.END_TURN, "m", usage, null, null, null);
    }

    ModelReplied truncatedReply(String text) {
        return new ModelReplied(callModel(), List.of(new TextBlock(text)), StopReason.MAX_TOKENS, "m", null, null, null, null);
    }

    /** 某个调用的结果，命令 ID 取发出它的 {@code RunTool}。 */
    ToolFinished finished(RunId run, String callId, String text, boolean error) {
        CommandId command = state.runs().get(run).tools().slots().stream()
                .filter(slot -> slot.call().id().equals(callId)).findFirst().orElseThrow().command();
        return new ToolFinished(command, callId, text, error, null);
    }

    static ToolCallBlock call(String id, String name) {
        return new ToolCallBlock(id, name, "{\"path\":\"a\"}", new Json.Obj(Map.of("path", new Json.Str("a"))));
    }

    static ToolCallBlock badCall(String id, String name) {
        return new ToolCallBlock(id, name, "{oops", null);
    }

    static TextBlock text(String text) {
        return new TextBlock(text);
    }

    /** 字段顺序和 ClaudeClient 写入的一样；Map.of 的顺序每次 JVM 启动都不同，会让输出格式的对比不稳定。 */
    static ReasoningBlock claudeThinking(String text) {
        Map<String, Json> raw = new java.util.LinkedHashMap<>();
        raw.put("type", new Json.Str("thinking"));
        raw.put("thinking", new Json.Str(text));
        raw.put("signature", new Json.Str("sig"));
        return new ReasoningBlock("anthropic", new Json.Obj(raw));
    }

    static ReasoningBlock redacted() {
        Map<String, Json> raw = new java.util.LinkedHashMap<>();
        raw.put("type", new Json.Str("redacted_thinking"));
        raw.put("data", new Json.Str("xxx"));
        return new ReasoningBlock("anthropic", new Json.Obj(raw));
    }

    static ReasoningBlock openAiThinking(String text) {
        return new ReasoningBlock("openai", new Json.Str(text));
    }
}
