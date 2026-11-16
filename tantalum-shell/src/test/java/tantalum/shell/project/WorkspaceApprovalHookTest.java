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
        assertSame(Allow.PLAIN, HOOK.beforeToolCall(KernelState.empty(), HookContext.of(RUN, Instant.EPOCH).withCall(call("read_file", "/x", null))));
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

    private static ToolCallBlock opens(String workspace) {
        Map<String, Json> fields = new LinkedHashMap<>();
        fields.put("task", new Json.Str("do it"));
        if (workspace != null) {
            fields.put("workspace", new Json.Str(workspace));
        }
        return new ToolCallBlock("d1", "delegate", "{}", new Json.Obj(fields));
    }

    private static HookResult opening(tantalum.value.state.SessionState session, String workspace) {
        return HOOK.beforeToolCall(KernelState.empty().with(session), HookContext.of(RUN, Instant.EPOCH).withCall(opens(workspace)));
    }

    /**
     * 模型在某个目录里开会话：只能是用户的工作区（上下文那一段列着的）、这个会话自己的目录、或者这个会话里刚确认新建的；
     * 别的目录拦下，不然“新建前先问”就被绕过去了。
     */
    @Test
    void aConversationCanOnlyBeOpenedInAFolderTheUserHasAsAWorkspace() {
        tantalum.value.state.SessionState session = tantalum.value.state.SessionState.start(RUN.session(), RUN.principal()).withDir("/home/u/here")
                .withContext(new tantalum.value.state.SessionContext("sys", List.of(), null, List.of(new tantalum.value.state.ContextSection(
                        "workspaces", "Workspaces are folders.\n- workspace \"App\" — /home/u/app\n- workspace \"Win\" — C:\\Work\\Site\n", true))));
        assertSame(Allow.PLAIN, opening(session, null), "no workspace given: same folder as this conversation");
        assertSame(Allow.PLAIN, opening(session, "/home/u/app"));
        assertSame(Allow.PLAIN, opening(session, "/home/u/app/"), "a trailing slash is the same folder");
        assertSame(Allow.PLAIN, opening(session, "c:/work/site"), "Windows paths compare without case and slash direction");
        assertSame(Allow.PLAIN, opening(session, "/home/u/here"), "this conversation's own folder");
        tantalum.value.hook.Stop stop = assertInstanceOf(tantalum.value.hook.Stop.class, opening(session, "/home/u/other"));
        assertTrue(stop.reason().contains("not one of the user's workspaces") && stop.reason().contains("asked to confirm"), stop.reason());
        assertInstanceOf(tantalum.value.hook.Stop.class, opening(session, "/home/u/app/../other"));
        assertInstanceOf(tantalum.value.hook.Stop.class, opening(session, "/home/u"), "a parent of a workspace is not a workspace");

        // 这个会话里用户刚点了“新建”：那一段还没更新，也认；点了“不建”（结果是错误）不认
        tantalum.value.state.SessionState added = session
                .append(new tantalum.value.model.Message(tantalum.value.model.Role.ASSISTANT, List.of(call("workspace_add", "/home/u/other", null)), false))
                .append(new tantalum.value.model.Message(tantalum.value.model.Role.USER,
                        List.of(new tantalum.value.model.ToolResultBlock("c1", "Added workspace \"other\" at /home/u/other.", false, null)), false));
        assertSame(Allow.PLAIN, opening(added, "/home/u/other"));
        tantalum.value.state.SessionState refused = session
                .append(new tantalum.value.model.Message(tantalum.value.model.Role.ASSISTANT, List.of(call("workspace_add", "/home/u/other", null)), false))
                .append(new tantalum.value.model.Message(tantalum.value.model.Role.USER,
                        List.of(new tantalum.value.model.ToolResultBlock("c1", "The user decided not to add this workspace.", true, null)), false));
        assertInstanceOf(tantalum.value.hook.Stop.class, opening(refused, "/home/u/other"));
    }
}
