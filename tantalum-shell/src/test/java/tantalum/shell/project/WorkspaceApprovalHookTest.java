package tantalum.shell.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.LinkedHashMap;
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
import tantalum.value.model.ToolCallBlock;
import tantalum.value.question.Answer;
import tantalum.value.question.Question;
import tantalum.value.state.KernelState;
import tantalum.value.text.Language;

/** 新建工作区先问：纯函数直接调。整条链（停下、回答、登记）见 {@code WorkspaceApiTest}。 */
class WorkspaceApprovalHookTest {

    private static final RunRef RUN = new RunRef(new Principal("u"), new SessionId("s"), new RunId("r"));
    private static final WorkspaceApprovalHook HOOK = new WorkspaceApprovalHook();

    private static ToolCallBlock call(String tool, String path, String title) {
        Map<String, Json> fields = new LinkedHashMap<>();
        fields.put("path", new Json.Str(path));
        if (title != null) {
            fields.put("title", new Json.Str(title));
        }
        return new ToolCallBlock("c1", tool, "{}", new Json.Obj(fields));
    }

    private static HookResult answered(String choice) {
        ToolCallBlock call = call("workspace_add", "/home/u/app", null);
        return HOOK.onAnswer(KernelState.empty(), HookContext.of(RUN, Instant.EPOCH).withCall(call)
                .withQuestion(WorkspaceApprovalHook.question(call), choice == null ? null : Answer.of(choice)));
    }

    @Test
    void addingAWorkspaceAsksWithTheFolderAndTheName() {
        Ask ask = assertInstanceOf(Ask.class, HOOK.beforeToolCall(KernelState.empty(),
                HookContext.of(RUN, Instant.EPOCH).withCall(call("workspace_add", " /home/u/app ", "App"))));
        Question question = ask.question();
        assertEquals("workspace-c1", question.id());
        assertEquals("把这个目录加成工作区吗？", question.title().in(Language.ZH));
        assertEquals("/home/u/app\n\n名字：App", question.detail().in(Language.ZH));
        assertEquals("/home/u/app\n\nName: App", question.detail().in(Language.EN));
        assertEquals(List.of("新建", "不建"), question.choices().stream().map(choice -> choice.label().in(Language.ZH)).toList());
        assertNull(ask.deadline(), "waits as long as needed");
        assertEquals("/home/u/app", WorkspaceApprovalHook.question(call("workspace_add", "/home/u/app", null)).detail().in(Language.JA));
    }

    @Test
    void otherToolsAreNotAskedAndItCannotBeSwitchedOff() {
        assertSame(Allow.PLAIN, HOOK.beforeToolCall(KernelState.empty(), HookContext.of(RUN, Instant.EPOCH).withCall(call("delegate", "/x", null))));
        assertSame(Allow.PLAIN, HOOK.beforeToolCall(KernelState.empty(), HookContext.of(RUN, Instant.EPOCH)));
        assertTrue(HOOK.mandatory());
        assertTrue(HOOK.failClosed());
    }

    @Test
    void yesAddsItNoStopsTheCallAndTellsTheModelNotToGoAround() {
        assertSame(Allow.PLAIN, answered(WorkspaceApprovalHook.ADD));
        assertTrue(assertInstanceOf(Stop.class, answered(WorkspaceApprovalHook.DENY)).reason().contains("Do not add it another way"));
        assertTrue(assertInstanceOf(Stop.class, answered(null)).reason().contains("before the deadline"));
        assertSame(Allow.PLAIN, HOOK.onAnswer(KernelState.empty(), HookContext.of(RUN, Instant.EPOCH)), "not its question");
    }
}
