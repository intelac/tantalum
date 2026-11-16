package tantalum.value.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.value.command.Send;
import tantalum.value.event.SessionMessage;
import tantalum.value.id.CommandId;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;
import tantalum.value.model.ToolCallBlock;

/** 会话之间说话用到的值：会话来话、送话、等会话回答、排队的来话、运行的主人。 */
class SessionMessagingTest {

    private static final SessionId A = new SessionId("a");
    private static final RunRef FROM = new RunRef(new Principal("alice"), A, new RunId("a_5"));

    private static SessionMessage request(String id) {
        return new SessionMessage(new CommandId(id), FROM, "Task:\n" + id, null, false, 1, null, null, null);
    }

    @Test
    void aMessageIsEitherWorkHandedOverOrAnAnswerToOne() {
        SessionMessage work = request("m-1");
        assertEquals("SessionMessage", work.type());
        assertEquals(1, work.version());
        assertFalse(work.isReply());
        SessionMessage answer = new SessionMessage(new CommandId("m-2"), FROM, null, new CommandId("m-1"), true, 1, null, null, null);
        assertTrue(answer.isReply());
        assertEquals("", answer.text(), "no text is an empty text");
        assertThrows(NullPointerException.class, () -> new SessionMessage(null, FROM, "x", null, false, 1, null, null, null));
        assertThrows(NullPointerException.class, () -> new SessionMessage(new CommandId("m"), null, "x", null, false, 1, null, null, null));
        assertEquals(new SessionId("b"), new Send(new CommandId("m-1"), FROM, new SessionId("b"), work).to());
    }

    @Test
    void messagesThatArriveWhileBusyWaitInOrder() {
        assertTrue(Inbox.EMPTY.isEmpty());
        assertEquals(Inbox.EMPTY, new Inbox(null));
        Inbox inbox = Inbox.EMPTY.add(request("m-1")).add(request("m-2"));
        assertEquals(List.of(request("m-1"), request("m-2")), inbox.waiting());
        assertEquals(List.of(request("m-2")), inbox.rest().waiting());
        SessionState session = SessionState.start(A, new Principal("alice"));
        assertEquals(Inbox.EMPTY, session.inbox());
        assertEquals(1, session.withInbox(Inbox.EMPTY.add(request("m-1"))).inbox().waiting().size());
    }

    @Test
    void waitingForAnotherConversationIsAPhaseAndASlotStatus() {
        AwaitingReply waiting = new AwaitingReply(Instant.parse("2026-11-16T01:10:00Z"), new CommandId("t"));
        assertEquals("AwaitingReply", waiting.name());
        ToolSlot slot = ToolSlot.pending(new ToolCallBlock("c1", "delegate", "{}", null), true).asked(new CommandId("m-1"));
        assertEquals(ToolSlot.Status.ASKED, slot.status());
        assertEquals(new CommandId("m-1"), slot.command());
        assertFalse(slot.isFinished());
        OwnerRef owner = new OwnerRef.OfMessage(new CommandId("m-1"), A, 1);
        assertEquals(new OwnerRef.OfMessage(new CommandId("m-1"), A, 1), owner);
    }
}
