package tantalum.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tantalum.value.hook.Allow;
import tantalum.value.hook.Ask;
import tantalum.value.hook.HookContext;
import tantalum.value.hook.HookResult;
import tantalum.value.hook.Stop;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.question.Answer;
import tantalum.value.question.Question;
import tantalum.value.state.KernelState;
import tantalum.value.state.SessionState;
import tantalum.value.text.Language;

/** 工具审批（waiting-state 需求 9）：调工具前问，回答决定放不放；“一直允许”记进会话。不经过管线，纯函数直接调。 */
class ToolApprovalHookTest {

    private static final Principal USER = new Principal("u");
    private static final SessionId SESSION = new SessionId("s");
    private static final RunRef RUN = new RunRef(USER, SESSION, new RunId("r"));
    private static final Json ARGS = new Json.Obj(Map.of("path", new Json.Str("a.txt")));
    private static final ToolCallBlock WRITE = new ToolCallBlock("c", "write_file", "{}", ARGS);
    private static final HookContext CONTEXT = HookContext.of(RUN, Instant.EPOCH).withCall(WRITE);
    private static final ToolApprovalHook HOOK = new ToolApprovalHook(Set.of("write_file"));

    private static HookResult beforeTool(KernelState state, String tool) {
        return HOOK.beforeToolCall(state, HookContext.of(RUN, Instant.EPOCH).withCall(new ToolCallBlock("c", tool, "{}", ARGS)));
    }

    private static HookResult answered(String choice) {
        Question question = ToolApprovalHook.question(WRITE);
        return HOOK.onAnswer(KernelState.empty(), CONTEXT.withQuestion(question, choice == null ? null : Answer.of(choice)));
    }

    @Test
    void listedToolAsksWithThreeChoicesForThisCall() {
        Ask ask = assertInstanceOf(Ask.class, beforeTool(KernelState.empty(), "write_file"));
        Question question = ask.question();
        assertEquals("approval-c", question.id(), "the same call keeps the same question");
        for (Language language : Language.values()) {
            assertTrue(question.title().in(language).contains("write_file"), language.id());
        }
        assertEquals("{\"path\":\"a.txt\"}", question.detail().in(Language.EN));
        assertEquals(List.of("允许这一次", "这个会话里一直允许", "拒绝"), question.choices().stream().map(choice -> choice.label().in(Language.ZH)).toList());
        assertEquals(List.of("Allow once", "Always allow in this session", "Deny"),
                question.choices().stream().map(choice -> choice.label().in(Language.EN)).toList());
        assertEquals(null, ask.deadline(), "approval waits as long as needed");
        assertSame(Allow.PLAIN, beforeTool(KernelState.empty(), "read_file"));
    }

    /** 会话里记着“一直允许”这个工具就不再问；别的工具、别的会话照样问。 */
    @Test
    void aToolAlwaysAllowedInThisSessionIsNotAskedAgain() {
        SessionState allowed = SessionState.start(SESSION, USER)
                .withNote(ToolApprovalHook.NOTE, new Json.Obj(Map.of("write_file", new Json.Bool(true))));
        assertSame(Allow.PLAIN, beforeTool(KernelState.empty().with(allowed), "write_file"));
        ToolApprovalHook two = new ToolApprovalHook(Set.of("write_file", "bash"));
        assertInstanceOf(Ask.class, two.beforeToolCall(KernelState.empty().with(allowed),
                HookContext.of(RUN, Instant.EPOCH).withCall(new ToolCallBlock("c", "bash", "{}", ARGS))));
        SessionState other = SessionState.start(new SessionId("other"), USER);
        HookContext otherContext = HookContext.of(new RunRef(USER, other.id(), new RunId("r2")), Instant.EPOCH).withCall(WRITE);
        assertInstanceOf(Ask.class, HOOK.beforeToolCall(KernelState.empty().with(allowed).with(other), otherContext));
    }

    @Test
    void allowOnceApprovesWithoutANote() {
        Allow allow = assertInstanceOf(Allow.class, answered(ToolApprovalHook.ONCE));
        assertTrue(allow.notes().isEmpty());
    }

    @Test
    void alwaysAllowInThisSessionApprovesAndNotesTheTool() {
        Allow allow = assertInstanceOf(Allow.class, answered(ToolApprovalHook.SESSION));
        assertEquals(List.of(new Allow.SessionNote(ToolApprovalHook.NOTE, new Json.Obj(Map.of("write_file", new Json.Bool(true))))), allow.notes());
    }

    /** 拒绝时告诉模型不要换个工具绕过去（同 DSH 电脑操作插件的拒绝说明）。 */
    @Test
    void denyBlocksOnlyThisCallAndSaysNotToWorkAround() {
        Stop stop = assertInstanceOf(Stop.class, answered(ToolApprovalHook.DENY));
        assertTrue(stop.reason().contains("the user denied this call to write_file"), stop.reason());
        assertTrue(stop.reason().contains("Do not retry"), stop.reason());
    }

    @Test
    void noAnswerBeforeTheDeadlineBlocks() {
        Stop stop = assertInstanceOf(Stop.class, answered(null));
        assertTrue(stop.reason().contains("no approval decision"), stop.reason());
    }

    /** onAnswer 对所有问题都会调用：别的插件或 ask_user 的问题不归这个钩子管。 */
    @Test
    void otherQuestionsAreIgnored() {
        Question other = new Question("ask-a1", tantalum.value.text.LocalText.of("Which?"), null, List.of(), true, null);
        assertSame(Allow.PLAIN, HOOK.onAnswer(KernelState.empty(), CONTEXT.withQuestion(other, Answer.of("x"))));
    }

    /** 策略插件：默认关、用户可以关，开着时出错按拦截处理（A2）。 */
    @Test
    void hookIsAnOptionalPolicyThatFailsClosed() {
        assertEquals(false, HOOK.mandatory());
        assertEquals(false, HOOK.defaultEnabled());
        assertEquals(true, HOOK.failClosed());
        assertEquals("toolApproval", HOOK.name());
    }
}
