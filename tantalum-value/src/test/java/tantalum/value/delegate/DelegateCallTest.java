package tantalum.value.delegate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tantalum.value.id.CommandId;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;

/** {@code delegate} 的参数怎么读、新会话的编号怎么算、送给对方的话怎么写。都是纯函数。 */
class DelegateCallTest {

    private static Json args(Object... pairs) {
        Map<String, Json> fields = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            Object value = pairs[i + 1];
            fields.put((String) pairs[i], value instanceof Boolean flag ? new Json.Bool(flag) : value instanceof Json json ? json
                    : new Json.Str((String) value));
        }
        return new Json.Obj(fields);
    }

    @Test
    void notWaitingIsTheDefault() {
        DelegateCall.Parsed call = DelegateCall.parse(args("task", "  count the files  ", "brief", " under src "));
        assertEquals("count the files", call.task());
        assertEquals("under src", call.brief());
        assertFalse(call.waits());
        assertNull(call.session());
        assertTrue(DelegateCall.parse(args("task", "x", "wait", true)).waits());
        assertTrue(DelegateCall.parse(args("task", "x", "wait", "True")).waits(), "a boolean written as a string");
        assertNull(DelegateCall.parse(args("task", "x", "brief", "   ")).brief(), "blank counts as absent");
        assertEquals(new SessionId("s-9"), DelegateCall.parse(args("task", "more", "session", "s-9")).session());
    }

    @Test
    void badArgumentsSayWhatIsWrong() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> DelegateCall.parse(new Json.Str("x"))).getMessage().contains("object"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> DelegateCall.parse(args())).getMessage().contains("\"task\""));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> DelegateCall.parse(args("task", "x", "wait", "maybe"))).getMessage()
                .contains("\"wait\""));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> DelegateCall.parse(args("task", new Json.Bool(true)))).getMessage()
                .contains("must be a string"));
        assertFalse(DelegateCall.parse(args("task", "x", "wait", new Json.Null(), "brief", new Json.Null())).waits());
    }

    @Test
    void aNewConversationsIdComesFromTheMessageIdAndSaysWhoOpenedIt() {
        assertEquals(new SessionId("s-a_7-0"), DelegateCall.sessionOf(new CommandId("a_7-0")));
        assertEquals(new SessionId("a"), DelegateCall.openerOf(new SessionId("s-a_7-0")).orElseThrow());
        assertEquals(new SessionId("s-a_7-0"), DelegateCall.openerOf(new SessionId("s-s-a_7-0_3-1")).orElseThrow(), "opened by an opened one");
        assertTrue(DelegateCall.openerOf(new SessionId("a")).isEmpty());
        assertTrue(DelegateCall.openerOf(new SessionId("s-abc123")).isEmpty(), "a conversation the user opened");
        assertTrue(DelegateCall.openerOf(new SessionId("s-_1-0")).isEmpty());
    }

    @Test
    void theMessageSaysItComesFromAnotherConversationNotTheUser() {
        String text = DelegateCall.text(DelegateCall.parse(args("task", "count the files", "brief", "under src")), new SessionId("a"));
        assertTrue(text.startsWith("This comes from another conversation of the same user (a), not typed by the user here."), text);
        assertTrue(text.endsWith("Task:\ncount the files\n\nWhat you need to know:\nunder src"), text);
        assertTrue(DelegateCall.text(DelegateCall.parse(args("task", "x")), new SessionId("a")).endsWith("Task:\nx"));
        assertEquals("count the files", DelegateCall.taskOf(text), "the title of the opened conversation is the task");
        assertEquals("x", DelegateCall.taskOf(DelegateCall.text(DelegateCall.parse(args("task", "x")), new SessionId("a"))));
        assertEquals("Task:\nnot a hand-off", DelegateCall.taskOf("Task:\nnot a hand-off"));
        assertNull(DelegateCall.taskOf(null));
    }
}
