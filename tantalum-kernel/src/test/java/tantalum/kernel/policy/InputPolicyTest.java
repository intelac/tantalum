package tantalum.kernel.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tantalum.kernel.policy.InputPolicy.Handling;
import tantalum.kernel.policy.InputPolicy.Rule;
import tantalum.value.event.Input;
import tantalum.value.state.SessionKind;

/** session-model 需求 5.1：哪种会话收哪种输入、收到怎么办，只在这张表里。表的每一格一个测试。 */
class InputPolicyTest {

    private static final Rule REFUSED = new Rule(false, null);
    private static final Rule START = new Rule(true, Handling.START);
    private static final Rule JOIN = new Rule(true, Handling.JOIN);

    // ---- 你说的 ----

    @Test
    void theMainSessionTakesAPromptAndHandlesItAsTheUserChose() {
        assertEquals(START, InputPolicy.of(SessionKind.MAIN, Input.Kind.PROMPT, false));
        assertEquals(JOIN, InputPolicy.of(SessionKind.MAIN, Input.Kind.PROMPT, true));
    }

    @Test
    void aRegularSessionTakesAPromptAndHandlesItAsTheUserChose() {
        assertEquals(START, InputPolicy.of(SessionKind.REGULAR, Input.Kind.PROMPT, false));
        assertEquals(JOIN, InputPolicy.of(SessionKind.REGULAR, Input.Kind.PROMPT, true));
    }

    @Test
    void aHelperSessionRefusesAPrompt() {
        assertEquals(REFUSED, InputPolicy.of(SessionKind.HELPER, Input.Kind.PROMPT, false));
        assertEquals(REFUSED, InputPolicy.of(SessionKind.HELPER, Input.Kind.PROMPT, true));
    }

    // ---- 交代 ----

    @Test
    void theMainSessionRefusesAnAssignment() {
        assertEquals(REFUSED, InputPolicy.of(SessionKind.MAIN, Input.Kind.ASSIGNMENT, false));
    }

    /** dispatch：主会话派活，普通会话收交代、为它开一轮（只收这个人的主会话的，由收件步骤看来处）。 */
    @Test
    void aRegularSessionTakesAnAssignmentAndStartsARunForIt() {
        assertEquals(START, InputPolicy.of(SessionKind.REGULAR, Input.Kind.ASSIGNMENT, false));
    }

    /** dispatch：记一笔只有主会话收，只记。 */
    @Test
    void onlyTheMainSessionTakesANoticeAndOnlyRecordsIt() {
        assertEquals(new InputPolicy.Rule(true, InputPolicy.Handling.RECORD), InputPolicy.of(SessionKind.MAIN, Input.Kind.NOTICE, false));
        assertEquals(REFUSED, InputPolicy.of(SessionKind.REGULAR, Input.Kind.NOTICE, false));
        assertEquals(REFUSED, InputPolicy.of(SessionKind.HELPER, Input.Kind.NOTICE, false));
    }

    @Test
    void aHelperSessionTakesAnAssignmentAndStartsARunForIt() {
        assertEquals(START, InputPolicy.of(SessionKind.HELPER, Input.Kind.ASSIGNMENT, false));
    }

    // ---- 结果 ----

    @Test
    void theMainSessionTakesAReportAndItJoinsTheRun() {
        assertEquals(JOIN, InputPolicy.of(SessionKind.MAIN, Input.Kind.REPORT, false));
    }

    @Test
    void aRegularSessionTakesAReportAndItJoinsTheRun() {
        assertEquals(JOIN, InputPolicy.of(SessionKind.REGULAR, Input.Kind.REPORT, false));
    }

    @Test
    void aHelperSessionRefusesAReport() {
        assertEquals(REFUSED, InputPolicy.of(SessionKind.HELPER, Input.Kind.REPORT, false));
    }

    // ---- 整张表 ----

    /** “并进这一轮”是你对自己说的话做的选择：别的输入不看它。 */
    @Test
    void theChoiceToJoinOnlyMattersForAPrompt() {
        for (SessionKind session : SessionKind.values()) {
            for (Input.Kind input : new Input.Kind[] {Input.Kind.ASSIGNMENT, Input.Kind.REPORT}) {
                assertEquals(InputPolicy.of(session, input, false), InputPolicy.of(session, input, true), session + " " + input);
            }
        }
    }

    /** 加了会话种类或输入种类，表里就得有它的一格：收的一定写了怎么办，不收的一定没写。 */
    @Test
    void everyCellIsDecided() {
        for (SessionKind session : SessionKind.values()) {
            for (Input.Kind input : Input.Kind.values()) {
                for (boolean join : new boolean[] {false, true}) {
                    Rule rule = InputPolicy.of(session, input, join);
                    assertNotNull(rule, session + " " + input);
                    if (rule.accepted()) {
                        assertNotNull(rule.handling(), session + " " + input);
                    } else {
                        assertNull(rule.handling(), session + " " + input);
                    }
                }
            }
        }
    }

    /** 只有一层：帮手不能再把事交代出去。 */
    @Test
    void onlyAHelperCannotAssign() {
        assertTrue(InputPolicy.mayAssign(SessionKind.MAIN));
        assertTrue(InputPolicy.mayAssign(SessionKind.REGULAR));
        assertFalse(InputPolicy.mayAssign(SessionKind.HELPER));
    }
}
