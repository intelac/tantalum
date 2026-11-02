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
import tantalum.value.state.OwnerRef;
import tantalum.value.state.RunState;
import tantalum.value.state.SessionState;

/** 用内置转移表逐个喂事件，记录序列。不启线程、不用 mock。 */
public final class LoopFixture {

    public static final Principal USER = new Principal("u");
    public static final SessionId SESSION = new SessionId("s");

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
        TransitionTable table = new TransitionTable();
        BuiltinSteps.register(table);
        kernel = new Kernel(table, new HookChain(List.of(hooks)),
                (tool, args) -> tool.startsWith("safe"), maxParallelTools);
    }

    public Decision feed(RunId run, Event event) {
        EventEnvelope envelope = new EventEnvelope(seq++, Instant.parse("2026-10-31T01:00:00Z"),
                USER, SESSION, run, event);
        journal.add(envelope);
        last = kernel.step(state, envelope);
        state = last.newState();
        commands.addAll(last.commands());
        return last;
    }

    public Decision prompt(String text) {
        return feed(null, new UserPrompt(new SubmissionId("sub" + seq), new RequestId("req" + seq),
                new OwnerRef.OfSession(SESSION), text));
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

    public Command onlyCommand() {
        if (last.commands().size() != 1) {
            throw new AssertionError("expected one command but got " + last.commands());
        }
        return last.commands().getFirst();
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
