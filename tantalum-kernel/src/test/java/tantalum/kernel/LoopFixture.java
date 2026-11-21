package tantalum.kernel;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import tantalum.kernel.hook.HookChain;
import tantalum.kernel.hook.KernelHook;
import tantalum.kernel.step.BuiltinSteps;
import tantalum.kernel.table.TransitionTable;
import tantalum.value.command.Command;
import tantalum.value.decision.Decision;
import tantalum.value.event.Event;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.UserPrompt;
import tantalum.value.id.CommandId;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;
import tantalum.value.id.SubmissionId;
import tantalum.value.json.Json;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.state.KernelState;
import tantalum.value.event.SessionCreated;
import tantalum.value.state.SessionKind;
import tantalum.value.state.RunState;
import tantalum.value.state.SessionState;
import tantalum.value.event.UserPromptFixture;

/** 用内置转移表逐个喂事件，记录序列。不启线程、不用 mock。 */
public final class LoopFixture {

    public static final Principal USER = new Principal("u");
    public static final SessionId SESSION = new SessionId("s");
    public static final Instant AT = Instant.parse("2026-10-31T01:00:00Z");

    public final Kernel kernel;
    public final List<EventEnvelope> journal = new ArrayList<>();
    /** 实时路径发出的全部命令，按顺序；回放测试拿它对比。 */
    public final List<Command> commands = new ArrayList<>();
    public KernelState state = KernelState.empty();
    public Decision last;
    private long seq;

    public LoopFixture(KernelHook... hooks) {
        this(10, hooks);
    }

    public LoopFixture(int maxParallelTools, KernelHook... hooks) {
        this(maxParallelTools, tantalum.value.assignment.AssignmentLimits.DEFAULT, hooks);
    }

    public LoopFixture(int maxParallelTools, tantalum.value.assignment.AssignmentLimits assignments, KernelHook... hooks) {
        TransitionTable table = new TransitionTable();
        BuiltinSteps.register(table);
        kernel = new Kernel(table, new HookChain(List.of(hooks)),
                (tool, args) -> tool.startsWith("safe"), new Limits(maxParallelTools, Limits.DEFAULT.maxWorkRounds(), assignments));
        // 会话先建好才收得到话：账里的第一个事件就是它，回放从这里开始
        create(SESSION, new SessionCreated(SessionKind.REGULAR, null, false, null));
    }

    /** 建一个会话（别的种类、带目录的、帮手会话）。 */
    public Decision create(SessionId session, SessionCreated created) {
        return feed(session, null, created);
    }

    public Decision feed(RunId run, Event event) {
        return feed(SESSION, run, event);
    }

    /** 喂给别的会话（帮手会话）；序号是所有会话共用的一串。 */
    public Decision feed(SessionId session, RunId run, Event event) {
        EventEnvelope envelope = new EventEnvelope(seq++, AT,
                USER, session, run, event);
        journal.add(envelope);
        KernelState before = state;
        last = kernel.step(state, envelope);
        // 每一步都要能写成账里的一行再套回来：去掉开头的消息和追加的消息都记得下（history-window），别的字段也一样
        tantalum.value.decision.StepRecord record = tantalum.value.decision.StepRecord.of(envelope, before, last);
        if (!record.apply(envelope, before).newState().equals(last.newState())) {
            throw new AssertionError("the step at seq " + envelope.seq() + " does not survive its journal record");
        }
        state = last.newState();
        commands.addAll(last.commands());
        return last;
    }

    public Decision prompt(String text) {
        return feed(null, UserPromptFixture.of(new SubmissionId("sub" + seq), new RequestId("req" + seq), text));
    }

    /** 你说的一句话，选了并进正在跑的这一轮。 */
    public Decision join(String text) {
        return feed(null, UserPromptFixture.join(new SubmissionId("sub" + seq), new RequestId("req" + seq), text));
    }

    /** 收件队列里排着的（不并进这一轮的），按先后。 */
    public List<tantalum.value.model.Message> queued() {
        return session().inbox().stream().filter(item -> !item.join()).map(tantalum.value.state.InboxItem::message).toList();
    }

    /** 收件队列里等着并进这一轮的，按先后。 */
    public List<tantalum.value.model.Message> joining() {
        return session().inbox().stream().filter(tantalum.value.state.InboxItem::join).map(tantalum.value.state.InboxItem::message).toList();
    }

    /** 收件队列里文字是 {@code text} 的那一条的编号；改队列按它找。 */
    public long item(String text) {
        return session().inbox().stream().filter(item -> item.message().content().getFirst() instanceof TextBlock first && first.text().equals(text))
                .findFirst().orElseThrow().id();
    }

    /** 排着的那几条各自带的模型选择（没带的为 null）。 */
    public List<tantalum.value.model.ModelChoice> queuedModels() {
        return session().inbox().stream().filter(item -> !item.join()).map(tantalum.value.state.InboxItem::model).toList();
    }

    public SessionState session() {
        return state.sessions().get(SESSION);
    }

    public RunState run() {
        RunId active = session().activeRun();
        return active == null ? latestRun() : state.runs().get(active);
    }

    private RunState latestRun() {
        return state.runs().values().stream()
                .max(java.util.Comparator.comparingLong(run -> run.ref().run().seq()))
                .orElseThrow();
    }

    public RunId runId() {
        return run().ref().run();
    }

    public String phase() {
        return run().phase().name();
    }

    /** 上一步唯一的命令；送达的回执和确认定时器不算（delivery 的记账，测试另看）。 */
    public Command onlyCommand() {
        List<Command> work = last.commands().stream().filter(command -> !bookkeeping(command)).toList();
        if (work.size() != 1) {
            throw new AssertionError("expected one command but got " + last.commands());
        }
        return work.getFirst();
    }

    public static boolean bookkeeping(Command command) {
        return command instanceof tantalum.value.command.Acknowledge
                || command instanceof tantalum.value.command.StartTimer timer && timer.delay().equals(tantalum.kernel.step.Outbox.ACK_AFTER);
    }

    public CommandId onlyCommandId() {
        return onlyCommand().id();
    }

    public static ToolCallBlock call(String id, String name) {
        return new ToolCallBlock(id, name, "{}", new Json.Obj(java.util.Map.of()));
    }

    public static List<ContentBlock> text(String text) {
        return List.of(new TextBlock(text));
    }
}
