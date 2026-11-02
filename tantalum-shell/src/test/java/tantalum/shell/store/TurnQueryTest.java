package tantalum.shell.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.kernel.Kernel;
import tantalum.kernel.hook.HookChain;
import tantalum.kernel.step.BuiltinSteps;
import tantalum.kernel.table.TransitionTable;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.journal.SessionFile;
import tantalum.shell.journal.SessionFileReader;
import tantalum.shell.journal.SessionPaths;
import tantalum.shell.store.IndexWriter;
import tantalum.shell.store.SessionIndex;
import tantalum.shell.store.SessionReplay;
import tantalum.shell.store.UsageLedger;
import tantalum.value.event.ContextAssembled;
import tantalum.value.event.Event;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.ModelFailed;
import tantalum.value.event.ModelReplied;
import tantalum.value.event.TimerFired;
import tantalum.value.event.UserPrompt;
import tantalum.value.id.CommandId;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;
import tantalum.value.id.SubmissionId;
import tantalum.value.model.FailureReason;
import tantalum.value.model.Message;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tantalum.value.state.OwnerRef;

class TurnQueryTest {

    private static final Principal USER = new Principal("u");
    private static final SessionId SESSION = new SessionId("s");
    private static final RunId RUN = RunId.of(SESSION, 0);

    @TempDir
    Path dir;

    private TurnQuery query;
    private SessionIndex index;
    private IndexWriter writer;
    private long seq;

    @BeforeEach
    void setUp() throws Exception {
        TransitionTable table = new TransitionTable();
        BuiltinSteps.register(table);
        Kernel kernel = new Kernel(table, new HookChain(List.of()), (tool, args) -> false, 10);
        index = SessionIndex.open(dir);
        writer = new IndexWriter(index, 100, java.time.Duration.ofHours(1));
        SessionPaths paths = new SessionPaths(dir);
        SessionReplay replay = new SessionReplay(paths, new SessionFileReader(EventCodec.withBuiltins()), new UsageLedger(index, writer), kernel);
        query = new TurnQuery(paths, replay, EventCodec.withBuiltins().mapper());
    }

    @AfterEach
    void tearDown() throws Exception {
        writer.close();
        index.close();
    }

    private void write(RunId run, Event event) throws Exception {
        try (SessionFile file = SessionFile.open(new SessionPaths(dir), USER, SESSION, EventCodec.withBuiltins())) {
            file.append(List.of(new EventEnvelope(seq++, Instant.EPOCH, USER, SESSION, run, event)));
        }
    }

    private void writeScript() throws Exception {
        write(null, new UserPrompt(new SubmissionId("a"), new RequestId("a"), new OwnerRef.OfSession(SESSION), "hi"));
        write(RUN, new ContextAssembled(new CommandId("s_0-0"), "system prompt", List.of(), null, List.of()));
        write(RUN, new ModelFailed(new CommandId("s_1-0"), FailureReason.SERVER_ERROR, true, null, "pa", "500"));
        write(RUN, new TimerFired(new CommandId("s_2-0")));
        write(RUN, new ModelReplied(new CommandId("s_3-0"), List.of(new TextBlock("answer")), StopReason.END_TURN, "m", null));
    }

    @Test
    void eachModelCallIsATurnWithItsFullPromptAndResult() throws Exception {
        writeScript();
        List<TurnQuery.Turn> turns = query.turns(USER, RUN);

        assertEquals(2, turns.size());
        assertEquals(1, turns.get(0).number());
        assertEquals("system prompt", turns.get(0).prompt().system());
        assertEquals(List.of(Message.user("hi")), turns.get(0).prompt().messages());
        assertEquals("500", turns.get(0).failure().detail());
        assertNull(turns.get(0).reply());
        assertEquals(List.of(new TextBlock("answer")), turns.get(1).reply().content());
    }

    @Test
    void anotherUserSeesNothing() throws Exception {
        writeScript();
        assertTrue(query.turns(new Principal("other"), RUN).isEmpty());
    }

    @Test
    void unknownRunHasNoTurns() throws Exception {
        writeScript();
        assertTrue(query.turns(USER, new RunId("nope")).isEmpty());
    }

    @Test
    void historyReturnsTheFactsAndItemsOfThatUserAfterAPosition() throws Exception {
        writeScript();
        var all = query.messagesAfter(USER, "-1.0");
        var later = query.messagesAfter(USER, "1.0");
        assertTrue(all.size() > later.size());
        assertEquals(List.of("0.0:PhaseChanged", "0.1:item", "0.2:item"),
                all.subList(0, 3).stream().map(message -> message.id() + ":" + message.event()).toList());
        assertTrue(all.get(1).data().contains("\"kind\":\"user\""), all.get(1).data());
        assertTrue(all.get(2).data().contains("\"kind\":\"run\""), all.get(2).data());
        assertTrue(later.stream().noneMatch(message -> message.id().startsWith("0.") || message.id().equals("1.0")));
        assertTrue(query.messagesAfter(new Principal("other"), "-1.0").isEmpty());
    }

    @Test
    void traceListsEventsPhasesTurnsAndHandlerSegments() throws Exception {
        writeScript();
        TurnQuery.Trace trace = query.trace(USER, RUN);

        assertEquals(List.of("UserPrompt", "ContextAssembled", "ModelFailed", "TimerFired", "ModelReplied"),
                trace.events().stream().map(TurnQuery.Trace.Entry::type).toList());
        assertEquals(List.of("AwaitingContext", "AwaitingModel", "AwaitingRetry", "AwaitingModel", "Ended"),
                trace.events().stream().map(TurnQuery.Trace.Entry::phase).toList());
        assertEquals(List.of(0L, 1L, 2L, 3L, 4L), trace.events().stream().map(TurnQuery.Trace.Entry::seq).toList());
        assertEquals(1, trace.events().get(1).turn());
        assertNull(trace.events().get(2).turn());
        assertEquals(2, trace.events().get(3).turn());
        assertEquals(List.of("ContextHandler", "ModelHandler", "TimerHandler", "ModelHandler"),
                trace.segments().stream().map(TurnQuery.Trace.Segment::handler).toList());
        assertEquals(Instant.EPOCH, trace.segments().getFirst().from());
        assertTrue(trace.segments().stream().allMatch(segment -> segment.to() != null));
    }

    /** 摘要调用是一个 turn（prompt 里工具被禁用），轨迹条目带压缩结果，AwaitingSummary 归 ModelHandler。 */
    @Test
    void summaryCallIsATurnAndTheTraceCarriesTheCompaction() throws Exception {
        tantalum.value.state.CompactionSettings settings = new tantalum.value.state.CompactionSettings(1000, 200, 70, 60, 30, 500);
        String q = ("q ").repeat(45);
        write(null, new UserPrompt(new SubmissionId("a"), new RequestId("a"), new OwnerRef.OfSession(SESSION), q));
        write(RUN, new ContextAssembled(new CommandId("s_0-0"), "system prompt", List.of(), settings, List.of()));
        write(RUN, new ModelReplied(new CommandId("s_1-0"), List.of(new TextBlock(("a ").repeat(45))), StopReason.END_TURN, "m",
                new tantalum.value.model.TokenUsage(700, 10, 0, 0)));
        write(null, new UserPrompt(new SubmissionId("b"), new RequestId("b"), new OwnerRef.OfSession(SESSION), q));
        write(RunId.of(SESSION, 3), new ContextAssembled(new CommandId("s_3-0"), "system prompt", List.of(), settings, List.of()));
        write(RunId.of(SESSION, 3), new ModelReplied(new CommandId("s_4-0"), List.of(new TextBlock(("b ").repeat(45))), StopReason.END_TURN, "m",
                new tantalum.value.model.TokenUsage(790, 10, 0, 0)));
        write(null, new UserPrompt(new SubmissionId("c"), new RequestId("c"), new OwnerRef.OfSession(SESSION), q));
        RunId second = RunId.of(SESSION, 6);
        write(second, new ContextAssembled(new CommandId("s_6-0"), "system prompt", List.of(), settings, List.of()));
        write(second, new ModelReplied(new CommandId("s_7-0"), List.of(new TextBlock("Goal: q")), StopReason.END_TURN, "m",
                new tantalum.value.model.TokenUsage(50, 20, 0, 0)));
        write(second, new ContextAssembled(new CommandId("s_8-0"), "system prompt", List.of(), settings, List.of()));
        write(second, new ModelReplied(new CommandId("s_9-0"), List.of(new TextBlock("a3")), StopReason.END_TURN, "m", null));

        List<TurnQuery.Turn> turns = query.turns(USER, second);
        assertEquals(2, turns.size());
        assertEquals(false, turns.get(0).prompt().output().toolsAllowed(), "turn 1 of the second run is the summary call");
        assertEquals(500, turns.get(0).prompt().output().maxTokens());
        assertTrue(turns.get(1).prompt().output().toolsAllowed());
        assertEquals(3, turns.get(1).prompt().messages().size(), "summary, kept a2, kept q3");

        TurnQuery.Trace trace = query.trace(USER, second);
        assertEquals(List.of("AwaitingContext", "AwaitingSummary", "AwaitingContext", "AwaitingModel", "Ended"),
                trace.events().stream().map(TurnQuery.Trace.Entry::phase).toList());
        TurnQuery.Trace.Entry summary = trace.events().get(2);
        assertEquals("ModelReplied", summary.type());
        assertTrue(summary.compaction().ok());
        assertEquals("AUTO", summary.compaction().trigger());
        assertTrue(summary.compaction().estimateAfter() < summary.compaction().estimateBefore());
        assertNull(trace.events().get(1).compaction());
        assertEquals(List.of("ContextHandler", "ModelHandler", "ContextHandler", "ModelHandler"),
                trace.segments().stream().map(TurnQuery.Trace.Segment::handler).toList());
    }

    @Test
    void traceOfAnUnfinishedRunHasAnOpenLastSegment() throws Exception {
        write(null, new UserPrompt(new SubmissionId("a"), new RequestId("a"), new OwnerRef.OfSession(SESSION), "hi"));
        write(RUN, new ContextAssembled(new CommandId("s_0-0"), "system prompt", List.of(), null, List.of()));
        TurnQuery.Trace trace = query.trace(USER, RUN);
        assertEquals(2, trace.segments().size());
        assertNull(trace.segments().getLast().to());
        assertEquals("ModelHandler", trace.segments().getLast().handler());
    }

    @Test
    void traceOfAnotherUsersRunIsEmpty() throws Exception {
        writeScript();
        assertTrue(query.trace(new Principal("other"), RUN).events().isEmpty());
        assertTrue(query.trace(USER, new RunId("nope")).events().isEmpty());
    }
}
