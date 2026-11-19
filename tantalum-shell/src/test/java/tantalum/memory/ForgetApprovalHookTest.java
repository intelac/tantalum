package tantalum.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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
import tantalum.value.model.Message;
import tantalum.value.model.Role;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolResultBlock;
import tantalum.value.question.Answer;
import tantalum.value.question.Question;
import tantalum.value.state.KernelState;
import tantalum.value.text.Language;

/** 忘掉之前先问：纯函数直接调。整条链（停下、回答、去掉）见 {@code ForgetApprovalApiTest}。 */
class ForgetApprovalHookTest {

    private static final RunRef RUN = new RunRef(new Principal("u"), new SessionId("s"), new RunId("r"));
    private static final ForgetApprovalHook HOOK = new ForgetApprovalHook();

    private static ToolCallBlock call(Map<String, Json> arguments) {
        return new ToolCallBlock("c1", "memory_delete", "{}", new Json.Obj(arguments));
    }

    private static Json places(String... ids) {
        return new Json.Arr(java.util.Arrays.stream(ids).<Json>map(Json.Str::new).toList());
    }

    @Test
    void lookingIsNotAskedButRemovingIs() {
        HookContext context = HookContext.of(RUN, Instant.EPOCH);
        assertSame(Allow.PLAIN, HOOK.beforeToolCall(KernelState.empty(), context.withCall(call(Map.of("text", new Json.Str("my plate"))))), "only looks");
        assertSame(Allow.PLAIN, HOOK.beforeToolCall(KernelState.empty(), context.withCall(new ToolCallBlock("c1", "memory_note", "{}", new Json.Obj(Map.of())))));
        assertSame(Allow.PLAIN, HOOK.beforeToolCall(KernelState.empty(), context));
        assertInstanceOf(Ask.class, HOOK.beforeToolCall(KernelState.empty(), context.withCall(call(Map.of("places", places("a@1#0"))))));
        Ask whole = assertInstanceOf(Ask.class, HOOK.beforeToolCall(KernelState.empty(), context.withCall(call(Map.of("name", new Json.Str("car"))))));
        assertEquals("把这条记忆整条删掉吗？", whole.question().title().in(Language.ZH));
        assertEquals("car", whole.question().detail().in(Language.ZH));
        assertFalse(HOOK.mandatory(), "it must not make the memory plugin impossible to switch off");
        assertTrue(HOOK.failClosed());
    }

    @Test
    void theCardShowsTheLinesWordForWordFromTheEarlierLookAndSaysWhenAnIdWasNeverListed() {
        List<Message> said = List.of(new Message(Role.USER, List.of(new ToolResultBlock("c0", "Nothing was changed yet. Places that may say it:\n"
                + "- id a@1#0 (entry car): My plate is 7Q2KD\n- id talk:4#0@s (a past conversation, said by the user): my plate is 7Q2KD ok\n"
                + "Call memory_delete again with places set to the ids of the ones that do say it.", false, null, List.of())), false));
        ToolCallBlock call = call(Map.of("places", places("a@1#0", "talk:4#0@s", "zz@9#9")));
        Question question = ForgetApprovalHook.question(call, null, List.of("a@1#0", "talk:4#0@s", "zz@9#9"), said);
        assertEquals("memory-forget-c1", question.id());
        assertEquals("忘掉这几行吗？", question.title().in(Language.ZH));
        assertEquals("- (entry car): My plate is 7Q2KD\n- (a past conversation, said by the user): my plate is 7Q2KD ok\n另有 1 处没有在这个对话里列出来过。",
                question.detail().in(Language.ZH));
        assertEquals(List.of("忘掉", "留着"), question.choices().stream().map(choice -> choice.label().in(Language.ZH)).toList());
    }

    @Test
    void forgetLetsItThroughKeepStopsItAndTellsTheModelNotToGoAround() {
        ToolCallBlock call = call(Map.of("name", new Json.Str("car")));
        Question question = ForgetApprovalHook.question(call, "car", List.of(), List.of());
        HookContext context = HookContext.of(RUN, Instant.EPOCH).withCall(call);
        assertSame(Allow.PLAIN, HOOK.onAnswer(KernelState.empty(), context.withQuestion(question, Answer.of(ForgetApprovalHook.FORGET))));
        HookResult kept = HOOK.onAnswer(KernelState.empty(), context.withQuestion(question, Answer.of(ForgetApprovalHook.KEEP)));
        assertTrue(assertInstanceOf(Stop.class, kept).reason().contains("Do not remove them another way"));
        assertTrue(assertInstanceOf(Stop.class, HOOK.onAnswer(KernelState.empty(), context.withQuestion(question, null))).reason().contains("nothing was removed"));
        assertSame(Allow.PLAIN, HOOK.onAnswer(KernelState.empty(), HookContext.of(RUN, Instant.EPOCH)), "not its question");
    }
}
