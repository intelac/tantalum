package tantalum.shell.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.kernel.Kernel;
import tantalum.kernel.Limits;
import tantalum.kernel.hook.HookChain;
import tantalum.kernel.hook.KernelHook;
import tantalum.kernel.step.BuiltinSteps;
import tantalum.kernel.table.TransitionTable;
import tantalum.shell.conversation.Transcript;
import tantalum.shell.conversation.TranscriptReplay;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.journal.SessionFileReader;
import tantalum.shell.journal.SessionPaths;
import tantalum.shell.loop.SessionSlice;
import tantalum.value.command.AssembleContext;
import tantalum.value.command.CallModel;
import tantalum.value.command.Command;
import tantalum.value.command.RunTool;
import tantalum.value.decision.Decision;
import tantalum.value.event.ContextAssembled;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.ModelReplied;
import tantalum.value.event.ToolFinished;
import tantalum.value.event.UserPrompt;
import tantalum.value.hook.Allow;
import tantalum.value.hook.HookContext;
import tantalum.value.hook.HookResult;
import tantalum.value.hook.Stop;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
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
import tantalum.value.state.EndReason;
import tantalum.value.state.Ended;
import tantalum.value.state.KernelState;
import tantalum.value.state.OwnerRef;
import tantalum.value.state.RunState;

/** 任务 25：单个会话重放和全量回放相同。 */
class SessionReplayTest {

    private static final Principal ALICE = new Principal("alice");
    private static final Principal BOB = new Principal("bob");
    private static final SessionId TOOLS = new SessionId("tools");
    private static final SessionId COMPACTED = new SessionId("compacted");
    private static final SessionId STOPPED = new SessionId("stopped");
    private static final SessionId BOBS = new SessionId("bobs");
    private static final CompactionSettings SETTINGS = new CompactionSettings(1000, 200, 70, 60, 30, 500);
    private static final Instant AT = Instant.parse("2026-11-02T01:00:00Z");
    private static final long QUOTA = 3000;

    /** 额度用完就停。它读该用户所有会话的累计，所以不注入用量时 {@code stopped} 会话的结果不同。 */
    private static final KernelHook QUOTA_HOOK = new KernelHook() {
        @Override public String name() { return "quota"; }
        @Override public HookResult beforeModelCall(KernelState state, HookContext context) {
            var principal = state.principals().get(context.run().principal());
            if (principal == null) {
                return Allow.PLAIN;
            }
            TokenUsage today = principal.usageOn(LocalDate.ofInstant(context.at(), ZoneOffset.UTC));
            return today.promptTotal() + today.output() >= QUOTA ? new Stop("quota") : Allow.PLAIN;
        }
    };

    @TempDir
    Path dir;

    private SessionIndex index;
    private IndexWriter writer;
    private IndexFixture fixture;
    private SessionPaths paths;
    private SessionReplay replay;
    private Kernel kernel;

    /** 待处理的命令，所有会话共用一个队列，所以不同会话和用户的事件交错。 */
    private record Pending(Principal principal, SessionId session, Command command) {}

    private final Deque<Pending> pending = new ArrayDeque<>();
    private final Deque<List<ContentBlock>> scripted = new ArrayDeque<>();
    private int requests;
    private int dynamic;

    @BeforeEach
    void start() throws Exception {
        TransitionTable table = new TransitionTable();
        BuiltinSteps.register(table);
        kernel = new Kernel(table, new HookChain(List.of(QUOTA_HOOK)), (tool, args) -> tool.startsWith("safe"), Limits.DEFAULT);
        index = SessionIndex.open(dir);
        writer = new IndexWriter(index, 100, Duration.ofHours(1));
        paths = new SessionPaths(dir);
        fixture = new IndexFixture(paths, writer::onDurable, writer::onStep, kernel);
        replay = new SessionReplay(paths, new SessionFileReader(EventCodec.withBuiltins()), new UsageLedger(index, writer), kernel);
    }

    @AfterEach
    void stop() throws Exception {
        fixture.files.close();
        writer.close();
        index.close();
    }

    private void track(Principal principal, SessionId session, Decision decision) {
        decision.commands().forEach(command -> pending.add(new Pending(principal, session, command)));
    }

    private void prompt(Principal principal, SessionId session, String text) throws Exception {
        String request = "req-" + requests++;
        track(principal, session, fixture.send(principal, session, null, AT,
                new UserPrompt(new SubmissionId("sub-" + request), new RequestId(request), new OwnerRef.OfSession(session), text)));
    }

    private static ToolCallBlock call(String id) {
        return new ToolCallBlock(id, "safe_read", "{\"p\":1}", new Json.Obj(java.util.Map.of("p", new Json.Num(java.math.BigDecimal.ONE))));
    }

    /** 走完一条命令。每次组装的动态内容都不同，相当于记忆变了。摘要调用不用脚本，脚本用完就直接回复。 */
    private void step() throws Exception {
        Pending next = pending.poll();
        var run = next.command().run().run();
        switch (next.command()) {
            case AssembleContext assemble -> track(next.principal(), next.session(), fixture.send(next.principal(), next.session(), run, AT,
                    new ContextAssembled(assemble.id(), "system", List.of(), SETTINGS,
                    List.of(new tantalum.value.state.ContextSection("memory-index", "memory-" + dynamic++, true)))));
            case CallModel model -> {
                boolean summary = !model.prompt().output().toolsAllowed();
                List<ContentBlock> content = summary ? List.of(new TextBlock("the summary of everything so far"))
                        : scripted.isEmpty() ? List.of(new TextBlock("done " + "x".repeat(90))) : scripted.poll();
                StopReason stop = content.stream().anyMatch(ToolCallBlock.class::isInstance) ? StopReason.TOOL_USE : StopReason.END_TURN;
                track(next.principal(), next.session(), fixture.send(next.principal(), next.session(), run, AT,
                        new ModelReplied(model.id(), content, stop, "m", new TokenUsage(summary ? 50 : 790, 10, 0, 0))));
            }
            case RunTool tool -> track(next.principal(), next.session(), fixture.send(next.principal(), next.session(), run, AT,
                    new ToolFinished(tool.id(), tool.call().id(), "result of " + tool.call().id(), false)));
            default -> { }
        }
    }

    private void drain() throws Exception {
        while (!pending.isEmpty()) {
            step();
        }
    }

    /** 工具轮和 thinking、压缩、其他用户、额度用完，全部交错。 */
    private void scenario() throws Exception {
        scripted.add(List.of(new ReasoningBlock("claude", new Json.Str("hmm")), new TextBlock("calling"), call("t1"), call("t2")));
        prompt(ALICE, TOOLS, "read two files " + "q".repeat(90));
        prompt(BOB, BOBS, "bob asks " + "q".repeat(90));
        prompt(ALICE, COMPACTED, "first " + "q".repeat(90));
        drain();
        prompt(ALICE, COMPACTED, "second, over the threshold " + "q".repeat(90));
        prompt(BOB, BOBS, "bob again");
        drain();
        prompt(ALICE, TOOLS, "third");
        drain();
        prompt(ALICE, STOPPED, "too late, the quota is used up");
        drain();
    }

    private record Full(KernelState state, Transcript transcript) {}

    private Full full() {
        Transcript transcript = new Transcript();
        List<EventEnvelope> events = new ArrayList<>(fixture.events);
        return new Full(TranscriptReplay.run(transcript, kernel, events), transcript);
    }

    private static List<RunState> sorted(List<RunState> runs) {
        return runs.stream().sorted(Comparator.comparing(run -> run.ref().run().value())).toList();
    }

    private void assertSameAsFull(Full full, SessionId session, SessionSlice slice) {
        assertEquals(full.state().sessions().get(session), slice.session());
        assertEquals(sorted(full.state().runs().values().stream().filter(run -> run.ref().session().equals(session)).toList()), sorted(slice.runs()));
        assertEquals(full.transcript().export(session), slice.transcript());
    }

    @Test
    void scenarioReallyCoversWhatItClaims() throws Exception {
        scenario();
        KernelState state = fixture.state();
        assertTrue(state.sessions().get(COMPACTED).compaction() != null, "compaction happened");
        assertTrue(state.sessions().get(TOOLS).messages().toString().contains("result of t2"), "tool round happened");
        Ended stopped = (Ended) state.runs().values().stream().filter(run -> run.ref().session().equals(STOPPED)).findFirst().orElseThrow().phase();
        assertEquals(EndReason.STOPPED, stopped.reason(), "the quota hook needs usage from other sessions");
    }

    @Test
    void replayingOneSessionEqualsItsPartOfTheFullReplay() throws Exception {
        scenario();
        Full full = full();
        assertEquals(fixture.state(), full.state(), "the full replay equals the live run");
        for (var session : List.of(Set.of(ALICE, TOOLS), Set.of(ALICE, COMPACTED), Set.of(ALICE, STOPPED), Set.of(BOB, BOBS))) {
            Principal principal = session.stream().filter(Principal.class::isInstance).map(Principal.class::cast).findFirst().orElseThrow();
            SessionId id = session.stream().filter(SessionId.class::isInstance).map(SessionId.class::cast).findFirst().orElseThrow();
            SessionSlice slice = replay.load(principal, id, Long.MAX_VALUE).orElseThrow();
            assertSameAsFull(full, id, slice);
        }
    }

    @Test
    void stopsBeforeTheGivenSeq() throws Exception {
        scenario();
        List<EventEnvelope> events = fixture.events.stream().filter(event -> event.session().equals(TOOLS)).toList();
        long cut = events.get(4).seq();
        SessionSlice slice = replay.load(ALICE, TOOLS, cut).orElseThrow();
        assertEquals(events.get(3).seq(), slice.seq());
    }

    @Test
    void nothingToReplay() throws Exception {
        assertEquals(Optional.empty(), replay.load(ALICE, new SessionId("none"), Long.MAX_VALUE));
        scenario();
        long first = fixture.events.stream().filter(event -> event.session().equals(TOOLS)).findFirst().orElseThrow().seq();
        assertEquals(Optional.empty(), replay.load(ALICE, TOOLS, first));
    }
}
