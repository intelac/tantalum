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

    private static final Principal ALICE = ReplayScenario.ALICE;
    private static final Principal BOB = ReplayScenario.BOB;
    private static final SessionId TOOLS = ReplayScenario.TOOLS;
    private static final SessionId COMPACTED = ReplayScenario.COMPACTED;
    private static final SessionId STOPPED = ReplayScenario.STOPPED;
    private static final SessionId BOBS = ReplayScenario.BOBS;

    @TempDir
    Path dir;

    private ReplayScenario scenario;
    private IndexFixture fixture;
    private SessionReplay replay;
    private Kernel kernel;

    @BeforeEach
    void start() throws Exception {
        scenario = new ReplayScenario(dir);
        fixture = scenario.fixture;
        kernel = scenario.kernel;
        replay = new SessionReplay(scenario.paths, new SessionFileReader(EventCodec.withBuiltins()),
                new UsageLedger(scenario.index, scenario.writer), kernel);
    }

    @AfterEach
    void stop() throws Exception {
        scenario.close();
    }

    private void scenario() throws Exception {
        scenario.run();
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
