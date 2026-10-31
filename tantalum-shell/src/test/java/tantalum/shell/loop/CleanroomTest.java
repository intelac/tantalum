package tantalum.shell.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.kernel.Kernel;
import tantalum.kernel.hook.HookChain;
import tantalum.kernel.table.BuiltinSteps;
import tantalum.kernel.table.TransitionTable;
import tantalum.shell.conversation.Transcript;
import tantalum.shell.conversation.TranscriptItem;
import tantalum.value.command.AssembleContext;
import tantalum.value.command.Command;
import tantalum.value.decision.Fact;
import tantalum.value.event.CommandDropped;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.UserPrompt;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.SessionId;
import tantalum.value.id.SubmissionId;
import tantalum.value.state.KernelState;
import tantalum.value.state.OwnerRef;

class CleanroomTest {

    private final List<Command> commands = new ArrayList<>();
    private final List<Fact> facts = new ArrayList<>();
    private final StateView view = new StateView(KernelState.empty());
    private boolean accept = true;
    private boolean halted;

    private static Kernel kernel() {
        TransitionTable table = new TransitionTable();
        BuiltinSteps.register(table);
        return new Kernel(table, new HookChain(List.of()), (tool, args) -> false, 10);
    }

    private static InputRing.Slot prompt(long seq) {
        SessionId session = new SessionId("s");
        InputRing.Slot slot = new InputRing.Slot();
        slot.envelope = new EventEnvelope(seq, Instant.EPOCH, new Principal("u"), session, null,
                new UserPrompt(new SubmissionId("sub"), new RequestId("req"), new OwnerRef.OfSession(session), "hi"));
        return slot;
    }

    private InputRing ring = new InputRing(8, (slot, seq, end) -> { }, (slot, seq, end) -> { });
    private final List<EventEnvelope> published = new ArrayList<>();

    private Cleanroom cleanroom() {
        InputRing captureRing = new InputRing(8, (slot, seq, end) -> published.add(slot.envelope), (slot, seq, end) -> { });
        captureRing.start();
        ring = captureRing;
        ResultPublisher publisher = new ResultPublisher(captureRing, new InFlightCommands(), Clock.systemUTC(), 0);
        return new Cleanroom(kernel(), KernelState.empty(), () -> halted,
                command -> accept && commands.add(command), Projection.NONE, (seq, index, fact) -> facts.add(fact), view, publisher);
    }

    @Test
    void stepsTheKernelAndPublishesCommandsFactsAndState() throws Exception {
        cleanroom().onEvent(prompt(0), 0, true);
        assertInstanceOf(AssembleContext.class, commands.getFirst());
        assertEquals(1, facts.size());
        assertEquals(1, view.current().runs().size());
        ring.shutdown();
    }

    @Test
    void stateAdvancesAcrossEvents() throws Exception {
        Cleanroom cleanroom = cleanroom();
        cleanroom.onEvent(prompt(0), 0, false);
        cleanroom.onEvent(prompt(1), 1, true);
        assertEquals(1, view.current().sessions().get(new SessionId("s")).followUps().size());
        ring.shutdown();
    }

    @Test
    void haltedJournalStopsTheKernel() throws Exception {
        halted = true;
        KernelState before = view.current();
        cleanroom().onEvent(prompt(0), 0, true);
        assertSame(before, view.current());
        assertEquals(List.of(), commands);
        ring.shutdown();
    }

    @Test
    void commandThatDoesNotFitIsReportedAsDropped() throws Exception {
        accept = false;
        cleanroom().onEvent(prompt(0), 0, true);
        for (int i = 0; i < 500 && published.isEmpty(); i++) {
            Thread.sleep(10);
        }
        assertTrue(published.getFirst().event() instanceof CommandDropped);
        ring.shutdown();
    }

    @Test
    void factListenerFailureDoesNotStopTheLoop() throws Exception {
        InputRing captureRing = new InputRing(8, (slot, seq, end) -> { }, (slot, seq, end) -> { });
        captureRing.start();
        ring = captureRing;
        Cleanroom cleanroom = new Cleanroom(kernel(), KernelState.empty(), () -> false, commands::add, Projection.NONE,
                (seq, index, fact) -> { throw new IllegalStateException("subscriber gone"); }, view,
                new ResultPublisher(captureRing, new InFlightCommands(), Clock.systemUTC(), 0));
        cleanroom.onEvent(prompt(0), 0, true);
        assertEquals(1, view.current().runs().size());
        ring.shutdown();
    }

    @Test
    void projectedItemsAreNumberedAfterTheFactsOfTheSameEvent() throws Exception {
        InputRing captureRing = new InputRing(8, (slot, seq, end) -> { }, (slot, seq, end) -> { });
        captureRing.start();
        ring = captureRing;
        List<String> notified = new ArrayList<>();
        FactListener listener = new FactListener() {
            @Override
            public void onFact(long seq, int index, Fact fact) {
                notified.add(seq + "." + index + ":" + fact.type());
            }

            @Override
            public void onItem(long seq, int index, Principal owner, TranscriptItem item) {
                notified.add(seq + "." + index + ":" + owner.value() + ":" + item.getClass().getSimpleName());
            }
        };
        Cleanroom cleanroom = new Cleanroom(kernel(), KernelState.empty(), () -> false, commands::add, new Transcript(),
                listener, view, new ResultPublisher(captureRing, new InFlightCommands(), Clock.systemUTC(), 0));
        cleanroom.onEvent(prompt(0), 0, true);
        assertEquals(List.of("0.0:PhaseChanged", "0.1:u:UserMessage", "0.2:u:RunStatus"), notified);
        ring.shutdown();
    }

    @Test
    void projectionFailureDoesNotStopTheLoop() throws Exception {
        InputRing captureRing = new InputRing(8, (slot, seq, end) -> { }, (slot, seq, end) -> { });
        captureRing.start();
        ring = captureRing;
        Projection broken = (envelope, decision) -> { throw new IllegalStateException("projection bug"); };
        Cleanroom cleanroom = new Cleanroom(kernel(), KernelState.empty(), () -> false, commands::add, broken,
                (seq, index, fact) -> facts.add(fact), view, new ResultPublisher(captureRing, new InFlightCommands(), Clock.systemUTC(), 0));
        cleanroom.onEvent(prompt(0), 0, true);
        assertEquals(1, view.current().runs().size());
        assertEquals(1, facts.size());
        ring.shutdown();
    }
}
