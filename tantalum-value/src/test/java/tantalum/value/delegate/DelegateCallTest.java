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

/** kernel-delegation：{@code delegate} 的参数怎么读、子会话编号怎么算、交给子会话的话怎么写。都是纯函数。 */
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
    void aNewTaskWaitsByDefault() {
        DelegateCall.Parsed call = DelegateCall.parse(args("task", "  count the files  ", "brief", " under src "));
        assertEquals("count the files", call.task());
        assertEquals("under src", call.brief());
        assertTrue(call.waits());
        assertNull(call.session());
        assertNull(call.answer());
        assertFalse(DelegateCall.parse(args("task", "x", "wait", false)).waits());
        assertFalse(DelegateCall.parse(args("task", "x", "wait", "False")).waits(), "a boolean written as a string");
        assertNull(DelegateCall.parse(args("task", "x", "brief", "   ")).brief(), "blank counts as absent");
    }

    @Test
    void continuingAndAnsweringNameTheSubConversation() {
        DelegateCall.Parsed more = DelegateCall.parse(args("task", "one more thing", "session", "d-s-1_7-0"));
        assertEquals(new SessionId("d-s-1_7-0"), more.session());
        DelegateCall.Parsed answer = DelegateCall.parse(args("session", "d-s-1_7-0", "answer", "use src/main"));
        assertEquals("use src/main", answer.answer());
        assertNull(answer.task());
    }

    @Test
    void badArgumentsSayWhatIsWrong() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> DelegateCall.parse(new Json.Str("x"))).getMessage().contains("object"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> DelegateCall.parse(args())).getMessage().contains("\"task\""));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> DelegateCall.parse(args("task", "x", "wait", "maybe"))).getMessage()
                .contains("\"wait\""));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> DelegateCall.parse(args("answer", "x"))).getMessage()
                .contains("\"session\""));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> DelegateCall.parse(args("task", new Json.Bool(true)))).getMessage()
                .contains("must be a string"));
        assertTrue(DelegateCall.parse(args("task", "x", "wait", new Json.Null(), "brief", new Json.Null())).waits());
    }

    @Test
    void theChildIdComesFromTheDelegationId() {
        assertEquals(new SessionId("d-s-1_7-0"), DelegateCall.childOf(new CommandId("s-1_7-0")));
        assertEquals(new SessionId("s-1"), DelegateCall.parentOf(new SessionId("d-s-1_7-0")).orElseThrow());
        assertEquals(new SessionId("d-s-1_7-0"), DelegateCall.parentOf(new SessionId("d-d-s-1_7-0_3-1")).orElseThrow(), "a grandchild");
        assertTrue(DelegateCall.parentOf(new SessionId("s-1")).isEmpty());
        assertTrue(DelegateCall.parentOf(new SessionId("d-abc123")).isEmpty(), "a sub-conversation from before, with a random id");
        assertTrue(DelegateCall.parentOf(new SessionId("d-_1-0")).isEmpty());
    }

    @Test
    void theFirstHandOffSaysWhereItCameFromLaterOnesOnlyTheirContent() {
        DelegateCall.Parsed call = DelegateCall.parse(args("task", "count the files", "brief", "under src"));
        String first = DelegateCall.text(call, new SessionId("s-1"), true);
        assertTrue(first.startsWith("You are doing a task delegated by another conversation of the same user (s-1)."), first);
        assertTrue(first.endsWith("Task:\ncount the files\n\nWhat you need to know:\nunder src"), first);
        assertEquals("Task:\ncount the files\n\nWhat you need to know:\nunder src", DelegateCall.text(call, new SessionId("s-1"), false));
        assertEquals("Task:\nx", DelegateCall.text(DelegateCall.parse(args("task", "x")), new SessionId("s-1"), false));
        assertEquals("use src/main", DelegateCall.text(DelegateCall.parse(args("session", "d-1", "answer", "use src/main")),
                new SessionId("s-1"), false));
    }
}
