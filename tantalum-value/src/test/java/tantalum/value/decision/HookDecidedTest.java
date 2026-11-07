package tantalum.value.decision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.value.hook.HookPoint;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;

/** review-fixes 需求 12.5 的折中：问题 id 仍在 reason 位（旧会话重写后的行要逐字节相同），但有自己的名字。 */
class HookDecidedTest {

    private static final RunRef RUN = new RunRef(new Principal("alice"), new SessionId("s"), new RunId("s_1"));

    @Test
    void theQuestionIdHasItsOwnNameAndStopsHaveNone() {
        HookDecided ask = new HookDecided(RUN, "approval", HookPoint.BEFORE_TOOL_CALL, HookDecided.Kind.ASK, "q-1", 0, List.of(), "c-1", null);
        assertEquals("q-1", ask.questionId());
        HookDecided stop = new HookDecided(RUN, "quota", HookPoint.BEFORE_MODEL_CALL, HookDecided.Kind.STOP, "over quota", 0, List.of(), null, null);
        assertNull(stop.questionId());
        assertEquals("over quota", stop.reason());
    }
}
