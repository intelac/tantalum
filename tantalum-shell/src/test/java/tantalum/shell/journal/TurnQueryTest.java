package tantalum.shell.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.kernel.Kernel;
import tantalum.kernel.hook.HookChain;
import tantalum.kernel.table.BuiltinSteps;
import tantalum.kernel.table.TransitionTable;
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
    private static final RunId RUN = new RunId("run-0");

    @TempDir
    Path dir;

    private Journal journal;
    private TurnQuery query;
    private long seq;

    @BeforeEach
    void setUp() throws Exception {
        journal = Journal.open(dir, EventCodec.withBuiltins());
        TransitionTable table = new TransitionTable();
        BuiltinSteps.register(table);
        query = new TurnQuery(journal, new Kernel(table, new HookChain(List.of()), (tool, args) -> false, 10), EventCodec.withBuiltins().mapper());
    }

    private void write(RunId run, Event event) throws Exception {
        journal.append(new EventEnvelope(seq++, Instant.EPOCH, USER, SESSION, run, event));
        journal.commit();
    }

    private void writeScript() throws Exception {
        write(null, new UserPrompt(new SubmissionId("a"), new RequestId("a"), new OwnerRef.OfSession(SESSION), "hi"));
        write(RUN, new ContextAssembled(new CommandId("0-0"), "system prompt", List.of()));
        write(RUN, new ModelFailed(new CommandId("1-0"), FailureReason.SERVER_ERROR, true, null, "pa", "500"));
        write(RUN, new TimerFired(new CommandId("2-0")));
        write(RUN, new ModelReplied(new CommandId("3-0"), List.of(new TextBlock("answer")), StopReason.END_TURN, "m", null));
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

    @Test
    void traceOfAnUnfinishedRunHasAnOpenLastSegment() throws Exception {
        write(null, new UserPrompt(new SubmissionId("a"), new RequestId("a"), new OwnerRef.OfSession(SESSION), "hi"));
        write(RUN, new ContextAssembled(new CommandId("0-0"), "system prompt", List.of()));
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
