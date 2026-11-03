package tantalum.shell.trace;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tantalum.kernel.Kernel;
import tantalum.kernel.Limits;
import tantalum.kernel.hook.HookChain;
import tantalum.kernel.hook.KernelHook;
import tantalum.kernel.step.BuiltinSteps;
import tantalum.kernel.table.TransitionTable;
import tantalum.value.command.CallModel;
import tantalum.value.command.Command;
import tantalum.value.command.DoWork;
import tantalum.value.command.StartTimer;
import tantalum.value.decision.Decision;
import tantalum.value.event.ContextAssembled;
import tantalum.value.event.Event;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.FollowUpSubmitted;
import tantalum.value.event.ModelFailed;
import tantalum.value.event.ModelReplied;
import tantalum.value.event.SteerSubmitted;
import tantalum.value.event.TimerFired;
import tantalum.value.event.ToolFinished;
import tantalum.value.event.UserPrompt;
import tantalum.value.event.WorkDone;
import tantalum.value.id.CommandId;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;
import tantalum.value.id.SubmissionId;
import tantalum.value.json.Json;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.FailureReason;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tantalum.value.model.TokenUsage;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolSpec;
import tantalum.value.state.CompactionSettings;
import tantalum.value.state.ContextSection;
import tantalum.value.state.KernelState;
import tantalum.value.state.OwnerRef;

/** 把事件喂给真实内核，再把 {@code (事件, 处理前状态, 决定)} 交给 {@link Trace}。每个事件时间前进 1 秒。 */
final class TraceFixture {

    static final Principal USER = new Principal("u");
    static final SessionId SESSION = new SessionId("s");
    static final Instant T0 = Instant.parse("2026-11-01T00:00:00Z");
    /** 窗口 1000、预留 200：会话估算超过 800 时自动压缩。 */
    static final CompactionSettings SETTINGS = new CompactionSettings(1000, 200, 70, 60, 30, 500);

    /** 内核处理过的一步，回放和“恢复后接着处理”的测试按它重喂。 */
    record Step(EventEnvelope envelope, KernelState before, Decision decision) {}

    /** 一次推送：事件序号和变化的记录。 */
    record Push(long seq, TraceRecord record) {}

    final Trace trace = new Trace();
    final List<Step> steps = new ArrayList<>();
    final List<Push> pushes = new ArrayList<>();
    private final Map<String, TraceRecord> latest = new LinkedHashMap<>();
    private final Kernel kernel;
    private KernelState state = KernelState.empty();
    private long seq;
    private Instant now = T0;
    private int submissions;

    TraceFixture() {
        this(true, List.of());
    }

    TraceFixture(boolean concurrencySafe, List<KernelHook> hooks) {
        TransitionTable table = new TransitionTable();
        BuiltinSteps.register(table);
        kernel = new Kernel(table, new HookChain(hooks), (tool, args) -> concurrencySafe, Limits.DEFAULT);
    }

    Decision feed(RunId run, Event event) {
        EventEnvelope envelope = new EventEnvelope(seq++, now, USER, SESSION, run, event);
        now = now.plusSeconds(1);
        KernelState before = state;
        Decision decision = kernel.step(before, envelope);
        state = decision.newState();
        steps.add(new Step(envelope, before, decision));
        for (TraceRecord record : trace.apply(envelope, before, decision)) {
            pushes.add(new Push(envelope.seq(), record));
            latest.put(record.id(), record);
        }
        return decision;
    }

    void tick(Duration delta) {
        now = now.plus(delta);
    }

    Instant now() {
        return now;
    }

    long lastSeq() {
        return seq - 1;
    }

    KernelState state() {
        return state;
    }

    /** 每条记录的最终版本，按 {@code pos} 排序，即页面看到的样子。 */
    List<TraceRecord> records() {
        return latest.values().stream().sorted(Comparator.comparing((TraceRecord record) -> seqOf(record.head().pos()))
                .thenComparing(record -> indexOf(record.head().pos()))).toList();
    }

    <T extends TraceRecord> List<T> records(Class<T> type) {
        return records().stream().filter(type::isInstance).map(type::cast).toList();
    }

    <T extends TraceRecord> T record(String id, Class<T> type) {
        return type.cast(latest.get(id));
    }

    static long seqOf(String pos) {
        return Long.parseLong(pos.substring(0, pos.indexOf('.')));
    }

    static int indexOf(String pos) {
        return Integer.parseInt(pos.substring(pos.indexOf('.') + 1));
    }

    RunId start(String text) {
        return start(text, null, List.of());
    }

    /** 开始一次运行并走完上下文组装，返回运行 ID。 */
    RunId start(String text, CompactionSettings settings, List<ContextSection> sections) {
        feed(null, prompt(text));
        RunId run = RunId.of(SESSION, lastSeq());
        assembled(run, settings, sections);
        return run;
    }

    void assembled(RunId run, CompactionSettings settings, List<ContextSection> sections) {
        feed(run, new ContextAssembled(lastOf(Command.class).id(), "sys", List.of(tool("read_file")), settings, sections));
    }

    UserPrompt prompt(String text) {
        int n = submissions++;
        return new UserPrompt(new SubmissionId("sub-" + n), new RequestId("req-" + n), new OwnerRef.OfSession(SESSION), text);
    }

    SteerSubmitted steer(String text) {
        int n = submissions++;
        return new SteerSubmitted(new SubmissionId("sub-" + n), new RequestId("req-" + n), text);
    }

    FollowUpSubmitted followUp(String text) {
        int n = submissions++;
        return new FollowUpSubmitted(new SubmissionId("sub-" + n), new RequestId("req-" + n), text);
    }

    /** 最近一次某类命令，不一定在最后一个决定里。 */
    <T extends Command> T lastOf(Class<T> type) {
        return steps.reversed().stream().flatMap(step -> step.decision().commands().stream())
                .filter(type::isInstance).map(type::cast).findFirst().orElseThrow();
    }

    CommandId callModel() {
        return lastOf(CallModel.class).id();
    }

    ModelReplied reply(ContentBlock... content) {
        return reply(null, content);
    }

    ModelReplied reply(TokenUsage usage, ContentBlock... content) {
        boolean tools = List.of(content).stream().anyMatch(ToolCallBlock.class::isInstance);
        return new ModelReplied(callModel(), List.of(content), tools ? StopReason.TOOL_USE : StopReason.END_TURN, "m", usage,
                null, null, null);
    }

    ModelFailed failed(FailureReason reason, boolean retryable, String detail) {
        return new ModelFailed(callModel(), reason, retryable, null, "", detail, null, null, null);
    }

    TimerFired timer() {
        return new TimerFired(lastOf(StartTimer.class).id());
    }

    ToolFinished finished(RunId run, String callId, String text, boolean error) {
        CommandId command = state.runs().get(run).tools().slots().stream()
                .filter(slot -> slot.call().id().equals(callId)).findFirst().orElseThrow().command();
        return new ToolFinished(command, callId, text, error, now);
    }

    WorkDone workDone(Json result, String continueText) {
        DoWork work = lastOf(DoWork.class);
        return new WorkDone(work.id(), work.workType(), result, false, continueText, now);
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

    static ToolSpec tool(String name) {
        return new ToolSpec(name, "d", new Json.Obj(Map.of()));
    }

    /** 90 个字符，约 30 个估算 token，用来把会话推到压缩阈值附近。 */
    static String block(String tag) {
        return (tag + " ").repeat(45).substring(0, 90);
    }
}
