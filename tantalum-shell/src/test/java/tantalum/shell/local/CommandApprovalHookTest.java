package tantalum.shell.local;

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
import tantalum.value.state.SessionState;
import tantalum.value.text.Language;

/** 命令先问（connector-jar 需求 5.1–5.3）：纯函数直接调，不经过管线。整条链（停下、回答、继续）见 {@code CommandApprovalFlowTest}。 */
class CommandApprovalHookTest {

    private static final Principal USER = new Principal("u");
    private static final SessionId SESSION = new SessionId("s");
    private static final RunRef RUN = new RunRef(USER, SESSION, new RunId("r"));
    private static final CommandApprovalHook HOOK = new CommandApprovalHook();

    private static ToolCallBlock call(String tool, String command, String workdir) {
        Map<String, Json> fields = new LinkedHashMap<>();
        fields.put("command", new Json.Str(command));
        if (workdir != null) {
            fields.put("workdir", new Json.Str(workdir));
        }
        return new ToolCallBlock("c1", tool, "{}", new Json.Obj(fields));
    }

    private static HookResult before(KernelState state, ToolCallBlock call) {
        return HOOK.beforeToolCall(state, HookContext.of(RUN, Instant.EPOCH).withCall(call));
    }

    private static HookResult answered(String choice) {
        ToolCallBlock call = call("bash", "ls", null);
        return HOOK.onAnswer(KernelState.empty(), HookContext.of(RUN, Instant.EPOCH).withCall(call)
                .withQuestion(CommandApprovalHook.question(call), choice == null ? null : Answer.of(choice)));
    }

    @Test
    void bashAndPowerShellAskWithTheCommandItself() {
        for (String tool : List.of("bash", "powershell")) {
            Ask ask = assertInstanceOf(Ask.class, before(KernelState.empty(), call(tool, "mvn test", "sub")));
            Question question = ask.question();
            assertEquals("command-c1", question.id());
            assertEquals("在你的电脑上执行这条命令吗？", question.title().in(Language.ZH));
            assertEquals("mvn test\n\n目录：sub", question.detail().in(Language.ZH));
            assertEquals("mvn test\n\nDirectory: sub", question.detail().in(Language.EN));
            assertEquals(List.of("允许", "这个会话都允许", "拒绝"), question.choices().stream().map(c -> c.label().in(Language.ZH)).toList());
            assertNull(ask.deadline(), "waits as long as needed");
        }
        assertEquals("ls", assertInstanceOf(Ask.class, before(KernelState.empty(), call("bash", "ls", null))).question().detail().in(Language.JA));
    }

    @Test
    void otherToolsAreNotAsked() {
        assertSame(Allow.PLAIN, before(KernelState.empty(), call("read_file", "x", null)));
        assertSame(Allow.PLAIN, before(KernelState.empty(), call("mcp__sqlite__query", "x", null)));
    }

    /** “这个会话都允许”记进会话；之后 bash 和 powershell 都不再问，别的会话照样问。 */
    @Test
    void allowAllInThisSession() {
        Allow allow = assertInstanceOf(Allow.class, answered(CommandApprovalHook.SESSION));
        assertEquals(1, allow.notes().size());
        SessionState allowed = SessionState.start(SESSION, USER).withNote(allow.notes().getFirst().key(), allow.notes().getFirst().value());
        KernelState state = KernelState.empty().with(allowed);
        assertSame(Allow.PLAIN, before(state, call("bash", "rm -rf build", null)));
        assertSame(Allow.PLAIN, before(state, call("powershell", "dir", null)));
        SessionState other = SessionState.start(new SessionId("other"), USER);
        HookContext elsewhere = HookContext.of(new RunRef(USER, other.id(), new RunId("r2")), Instant.EPOCH).withCall(call("bash", "ls", null));
        assertInstanceOf(Ask.class, HOOK.beforeToolCall(state.with(other), elsewhere));
    }

    /** 和例子插件的审批共用 {@code approval} 记录：合并，不互相覆盖。 */
    @Test
    void sharesTheApprovalNoteWithoutOverwriting() {
        SessionState both = SessionState.start(SESSION, USER)
                .withNote(CommandApprovalHook.NOTE, new Json.Obj(Map.of("write_file", new Json.Bool(true))))
                .withNote(CommandApprovalHook.NOTE, new Json.Obj(Map.of(CommandApprovalHook.SHELL, new Json.Bool(true))));
        assertEquals(2, both.note(CommandApprovalHook.NOTE).fields().size());
    }

    @Test
    void allowOnceDenyAndNoAnswer() {
        assertSame(Allow.PLAIN, answered(CommandApprovalHook.ONCE));
        Stop deny = assertInstanceOf(Stop.class, answered(CommandApprovalHook.DENY));
        assertTrue(deny.reason().contains("denied running this command"), deny.reason());
        assertInstanceOf(Stop.class, answered(null));
    }

    @Test
    void otherQuestionsAreNotOurs() {
        Question foreign = new Question("approval-x", tantalum.value.text.LocalText.of("?"), tantalum.value.text.LocalText.of(""),
                List.of(new Question.Choice("deny", tantalum.value.text.LocalText.of("no"))));
        assertSame(Allow.PLAIN, HOOK.onAnswer(KernelState.empty(), HookContext.of(RUN, Instant.EPOCH).withQuestion(foreign, Answer.of("deny"))));
    }

    @Test
    void longCommandsAreCut() {
        String command = "x".repeat(3000);
        String shown = assertInstanceOf(Ask.class, before(KernelState.empty(), call("bash", command, null))).question().detail().in(Language.EN);
        assertEquals(2001, shown.length());
        assertTrue(shown.endsWith("…"));
    }

    @Test
    void onByDefaultAndFailsClosed() {
        assertTrue(HOOK.defaultEnabled());
        assertTrue(HOOK.failClosed());
        assertEquals("commandApproval", HOOK.name());
    }
}
