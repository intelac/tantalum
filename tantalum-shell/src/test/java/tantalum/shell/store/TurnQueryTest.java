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
import tantalum.kernel.Limits;
import tantalum.kernel.hook.HookChain;
import tantalum.kernel.step.BuiltinSteps;
import tantalum.kernel.table.TransitionTable;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.journal.ResultWriter;
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
    private ResultWriter journal;
    private SessionIndex index;
    private IndexWriter writer;
    private long seq;

    @BeforeEach
    void setUp() throws Exception {
        TransitionTable table = new TransitionTable();
        BuiltinSteps.register(table);
        Kernel kernel = new Kernel(table, new HookChain(List.of()), (tool, args) -> false, Limits.DEFAULT);
        index = SessionIndex.open(dir);
        writer = new IndexWriter(index, 100, java.time.Duration.ofHours(1));
        SessionPaths paths = new SessionPaths(dir);
        journal = new ResultWriter(paths, EventCodec.withBuiltins(), kernel);
        query = new TurnQuery(paths, new RecordReplay(paths, new SessionFileReader(EventCodec.withBuiltins())), EventCodec.withBuiltins().mapper());
    }

    @AfterEach
    void tearDown() throws Exception {
        journal.close();
        writer.close();
        index.close();
    }

    private void write(RunId run, Event event) throws Exception {
        journal.write(new EventEnvelope(seq++, Instant.EPOCH, USER, SESSION, run, event));
    }

    private void writeScript() throws Exception {
        write(null, new UserPrompt(new SubmissionId("a"), new RequestId("a"), new OwnerRef.OfSession(SESSION), "hi"));
        write(RUN, new ContextAssembled(new CommandId("s_0-0"), "system prompt", List.of(), null, List.of()));
        write(RUN, new ModelFailed(new CommandId("s_1-0"), FailureReason.SERVER_ERROR, true, null, "pa", "500", null, null, null));
        write(RUN, new TimerFired(new CommandId("s_2-0")));
        write(RUN, new ModelReplied(new CommandId("s_3-0"), List.of(new TextBlock("answer")), StopReason.END_TURN, "m", null, null, null, null));
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

    /** 摘要调用是一个 turn，prompt 里工具被禁用；压缩结果在轨迹里看（TracePassTest）。 */
    @Test
    void summaryCallIsATurnWithToolsDisabled() throws Exception {
        tantalum.value.state.CompactionSettings settings = new tantalum.value.state.CompactionSettings(1000, 200, 70, 60, 30, 500);
        String q = ("q ").repeat(45);
        write(null, new UserPrompt(new SubmissionId("a"), new RequestId("a"), new OwnerRef.OfSession(SESSION), q));
        write(RUN, new ContextAssembled(new CommandId("s_0-0"), "system prompt", List.of(), settings, List.of()));
        write(RUN, new ModelReplied(new CommandId("s_1-0"), List.of(new TextBlock(("a ").repeat(45))), StopReason.END_TURN, "m",
                new tantalum.value.model.TokenUsage(700, 10, 0, 0), null, null, null));
        write(null, new UserPrompt(new SubmissionId("b"), new RequestId("b"), new OwnerRef.OfSession(SESSION), q));
        write(RunId.of(SESSION, 3), new ContextAssembled(new CommandId("s_3-0"), "system prompt", List.of(), settings, List.of()));
        write(RunId.of(SESSION, 3), new ModelReplied(new CommandId("s_4-0"), List.of(new TextBlock(("b ").repeat(45))), StopReason.END_TURN, "m",
                new tantalum.value.model.TokenUsage(790, 10, 0, 0), null, null, null));
        write(null, new UserPrompt(new SubmissionId("c"), new RequestId("c"), new OwnerRef.OfSession(SESSION), q));
        RunId second = RunId.of(SESSION, 6);
        write(second, new ContextAssembled(new CommandId("s_6-0"), "system prompt", List.of(), settings, List.of()));
        write(second, new ModelReplied(new CommandId("s_7-0"), List.of(new TextBlock("Goal: q")), StopReason.END_TURN, "m",
                new tantalum.value.model.TokenUsage(50, 20, 0, 0), null, null, null));
        write(second, new ContextAssembled(new CommandId("s_8-0"), "system prompt", List.of(), settings, List.of()));
        write(second, new ModelReplied(new CommandId("s_9-0"), List.of(new TextBlock("a3")), StopReason.END_TURN, "m", null, null, null, null));

        List<TurnQuery.Turn> turns = query.turns(USER, second);
        assertEquals(2, turns.size());
        assertEquals(false, turns.get(0).prompt().output().toolsAllowed(), "turn 1 of the second run is the summary call");
        assertEquals(500, turns.get(0).prompt().output().maxTokens());
        assertTrue(turns.get(1).prompt().output().toolsAllowed());
        assertEquals(3, turns.get(1).prompt().messages().size(), "summary, kept a2, kept q3");

    }

}
