package tantalum.value.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.value.command.CancelDelegate;
import tantalum.value.command.Delegate;
import tantalum.value.command.Return;
import tantalum.value.event.DelegateCancelled;
import tantalum.value.event.DelegateReturned;
import tantalum.value.event.Delegated;
import tantalum.value.event.Recovered;
import tantalum.value.id.CommandId;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;

/** kernel-delegation：会话在委派里的位置，和委派的事件、命令、状态这些值。 */
class HandoffsTest {

    private static final SessionId PARENT = new SessionId("s-1");
    private static final SessionId CHILD = new SessionId("d-s-1_7-0");
    private static final RunRef FROM = new RunRef(new Principal("alice"), PARENT, new RunId("s-1_5"));

    private static Delegated handoff(String id) {
        return new Delegated(new CommandId(id), FROM, Delegated.Kind.NEW, "Task:\n" + id, null, 1, null, null, null, List.of("grep"));
    }

    private static DelegateReturned outcome(String id, String text) {
        return new DelegateReturned(new CommandId(id), CHILD, new RunId("r"), DelegateReturned.Kind.RESULT, text, null);
    }

    @Test
    void anOrdinarySessionHasNoPlace() {
        assertFalse(Handoffs.NONE.isChild());
        assertEquals(0, Handoffs.NONE.depth());
        assertTrue(Handoffs.NONE.queued().isEmpty() && Handoffs.NONE.returned().isEmpty() && Handoffs.NONE.open().isEmpty());
        assertEquals(Handoffs.NONE, new Handoffs(null, 0, null, null, null, null));
        SessionState session = SessionState.start(PARENT, new Principal("alice"));
        assertEquals(Handoffs.NONE, session.handoffs());
        assertEquals(1, session.withHandoffs(Handoffs.NONE.under(PARENT, 1, null)).handoffs().depth());
    }

    @Test
    void theParentIsFixedByTheFirstHandOff() {
        Handoffs child = Handoffs.NONE.under(PARENT, 1, List.of("grep"));
        assertTrue(child.isChild());
        assertEquals(List.of("grep"), child.tools());
        assertEquals(child, child.under(new SessionId("other"), 2, null), "later hand-offs do not move it");
    }

    @Test
    void handOffsQueueInOrderAndOutcomesKeepTheLatestFew() {
        Handoffs place = Handoffs.NONE.under(PARENT, 1, null).enqueue(handoff("a")).enqueue(handoff("b"));
        assertTrue(place.isQueued(new CommandId("a")) && place.isQueued(new CommandId("b")));
        assertEquals(List.of(handoff("b")), place.dequeue().queued());
        Handoffs settled = place.settle(outcome("a", "first")).settle(outcome("a", "second"));
        assertEquals("second", settled.outcomeOf(new CommandId("a")).orElseThrow().text(), "a later outcome replaces the earlier one");
        assertEquals(1, settled.returned().size());
        for (int i = 0; i < Handoffs.KEEP + 3; i++) {
            settled = settled.settle(outcome("n" + i, "x"));
        }
        assertEquals(Handoffs.KEEP, settled.returned().size());
        assertTrue(settled.outcomeOf(new CommandId("a")).isEmpty(), "the oldest are dropped");
    }

    @Test
    void theParentRemembersWhatItHandedOffUntilItComesBack() {
        Handoffs place = Handoffs.NONE.send(new Handoffs.Sent(CHILD, handoff("a")));
        assertEquals(CHILD, place.sent(new CommandId("a")).orElseThrow().child());
        assertTrue(place.sent(new CommandId("b")).isEmpty());
        assertTrue(place.close(new CommandId("a")).open().isEmpty());
        assertEquals(place, place.close(new CommandId("b")));
    }

    @Test
    void theEventsCommandsAndThePhaseAreChecked() {
        assertEquals("Delegated", handoff("a").type());
        assertEquals(1, handoff("a").version());
        assertThrows(NullPointerException.class, () -> new Delegated(null, FROM, Delegated.Kind.NEW, "x", null, 1, null, null, null, null));
        assertNull(new Delegated(new CommandId("a"), FROM, Delegated.Kind.CONTINUE, "x", null, 1, null, null, null, null).tools());
        DelegateReturned back = new DelegateReturned(new CommandId("a"), CHILD, null, DelegateReturned.Kind.FAILED, null, null);
        assertEquals("", back.text(), "no text is an empty text");
        assertEquals("DelegateReturned", back.type());
        assertEquals(1, back.version());
        assertThrows(NullPointerException.class, () -> new DelegateReturned(new CommandId("a"), null, null, DelegateReturned.Kind.RESULT, "", null));
        assertEquals("Recovered", new Recovered().type());
        assertEquals(1, new Recovered().version());
        assertEquals("DelegateCancelled", new DelegateCancelled(new CommandId("a")).type());
        assertEquals(1, new DelegateCancelled(new CommandId("a")).version());
        assertThrows(NullPointerException.class, () -> new DelegateCancelled(null));

        Delegate send = new Delegate(new CommandId("a"), FROM, CHILD, handoff("a"));
        assertEquals(CHILD, send.child());
        Return ret = new Return(new CommandId("r-1"), FROM, PARENT, back);
        assertEquals(PARENT, ret.parent());
        CancelDelegate cancel = new CancelDelegate(new CommandId("c-1"), FROM, List.of(new CancelDelegate.Target(CHILD, new CommandId("a"))));
        assertEquals(CHILD, cancel.targets().getFirst().child());

        AwaitingDelegate waiting = new AwaitingDelegate(Instant.parse("2026-11-16T01:10:00Z"), new CommandId("t"));
        assertEquals("AwaitingDelegate", waiting.name());
        ToolSlot slot = ToolSlot.pending(new tantalum.value.model.ToolCallBlock("c1", "delegate", "{}", null), true)
                .delegated(new CommandId("a"));
        assertEquals(ToolSlot.Status.DELEGATED, slot.status());
        assertEquals(new CommandId("a"), slot.command());
        assertFalse(slot.isFinished());
    }
}
