package tantalum.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.WorkResult;
import tantalum.shell.plugin.Workspace;
import tantalum.value.event.WorkDone;
import tantalum.value.hook.Allow;
import tantalum.value.hook.HookContext;
import tantalum.value.hook.NeedWork;
import tantalum.value.hook.Stop;
import tantalum.value.id.CommandId;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.Message;
import tantalum.value.model.Role;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolResultBlock;
import tantalum.value.state.KernelState;
import tantalum.value.state.SessionState;

/**
 * A3：引用核对不读服务器上的文件，只拿会话里 {@code read_file}、{@code grep} 的结果核对。测试里没有任何文件，
 * 引用有没有根据全看会话里的工具结果。
 */
class CitationCheckTest {

    private static final Principal USER = new Principal("u");
    private static final SessionId SESSION = new SessionId("s");
    private static final HookContext CONTEXT = HookContext.of(new RunRef(USER, SESSION, new RunId("r")), Instant.EPOCH);
    private static final CitationCheckHook HOOK = new CitationCheckHook();

    @org.junit.jupiter.api.io.TempDir
    Path empty;

    private static Message assistant(ContentBlock... blocks) {
        return new Message(Role.ASSISTANT, List.of(blocks), false);
    }

    private static Message result(String call, String text, boolean error) {
        return new Message(Role.USER, List.of(new ToolResultBlock(call, text, error, null)), false);
    }

    private static ToolCallBlock call(String id, String tool, String path, Integer offset) {
        java.util.Map<String, Json> fields = new java.util.LinkedHashMap<>();
        fields.put(tool.equals("grep") ? "pattern" : "path", new Json.Str(path));
        if (offset != null) {
            fields.put("offset", new Json.Num(java.math.BigDecimal.valueOf(offset)));
        }
        return new ToolCallBlock(id, tool, "{}", new Json.Obj(fields));
    }

    /** 读了 src/Order.java 的第 10–12 行（offset 10，三行），grep 命中 src/Pay.java 第 40 行。 */
    private static SessionState session(String answer, String... corrections) {
        SessionState session = SessionState.start(SESSION, USER)
                .append(Message.user("where is the total computed?"))
                .append(assistant(call("c1", "read_file", "src/Order.java", 10)))
                .append(result("c1", "int a;\nint b;\nint total = a + b;", false))
                .append(assistant(call("c2", "grep", "charge", null)))
                .append(result("c2", "src/Pay.java:40:    charge(total);\nsrc/Pay.java:41:    log();", false))
                .append(assistant(call("c3", "read_file", "Missing.java", null)))
                .append(result("c3", "Not a file: Missing.java", true));
        for (String correction : corrections) {
            session = session.append(assistant(new TextBlock("see Nope.java:1"))).append(Message.user(correction));
        }
        return session.append(assistant(new TextBlock(answer)));
    }

    private static tantalum.value.hook.HookResult check(SessionState session) {
        return HOOK.beforeRunEnd(KernelState.empty().with(session), CONTEXT);
    }

    @Test
    void citationsOfLinesThatWereReadPass() {
        assertSame(Allow.PLAIN, check(session("Computed at Order.java:12, charged at src/Pay.java:40.")));
        assertSame(Allow.PLAIN, check(session("No references here.")));
    }

    /** 读到第 12 行，引用第 13 行；没读过的文件；读失败的文件；grep 只命中第 40、41 行。 */
    @Test
    void citationsWithoutABasisGoBackToTheModel() {
        NeedWork need = assertInstanceOf(NeedWork.class,
                check(session("See Order.java:13, Other.java:1, Missing.java:1, Pay.java:42 and Order.java:13 again.")));
        assertEquals(CitationWork.TYPE, need.workType());
        assertEquals(CitationCheckHook.input(List.of("Order.java:13", "Other.java:1", "Missing.java:1", "Pay.java:42")), need.input());
    }

    /** 没读完时以结尾的 “Showing lines a-b” 为准。 */
    @Test
    void aPartialReadCountsOnlyTheLinesShown() {
        SessionState session = SessionState.start(SESSION, USER)
                .append(Message.user("q"))
                .append(assistant(call("c1", "read_file", "Big.java", null)))
                .append(result("c1", "l1\nl2\n\n[Showing lines 1-2000 of 5000. Use offset=2001 to continue.]", false))
                .append(assistant(new TextBlock("Big.java:2000 and Big.java:2001")));
        NeedWork need = assertInstanceOf(NeedWork.class, check(session));
        assertEquals(CitationCheckHook.input(List.of("Big.java:2001")), need.input());
    }

    @Test
    void afterTwoCorrectionsTheRunStopsWithTheAnswerKept() {
        String correction = CitationWork.CORRECTION + ": Nope.java:1.";
        assertInstanceOf(NeedWork.class, check(session("see Other.java:1", correction)));
        Stop stop = assertInstanceOf(Stop.class, check(session("see Other.java:1", correction, correction)));
        assertTrue(stop.reason().contains("Other.java:1"), stop.reason());
    }

    @Test
    void theWorkWritesTheCorrectionWithoutReadingAnything() throws Exception {
        // 工作目录是空的：结果只来自钩子给的名单
        ToolContext context = new ToolContext(USER, SESSION, new Workspace(empty));
        WorkResult wrong = new CitationWork().run(context, CitationCheckHook.input(List.of("A.java:9", "B.java:1")));
        assertTrue(wrong.continueText().startsWith(CitationWork.CORRECTION), wrong.continueText());
        assertTrue(wrong.continueText().contains("A.java:9, B.java:1"));
        assertNull(new CitationWork().run(context, CitationCheckHook.input(List.of())).continueText());
    }

    /** 核对本身失败（工作超时、插件被关）不能当作通过。 */
    @Test
    void failedWorkStopsTheRunInsteadOfPassing() {
        WorkDone failed = new WorkDone(new CommandId("c"), CitationWork.TYPE, new Json.Str("work timed out"), true, null, null);
        Stop stop = assertInstanceOf(Stop.class, HOOK.beforeRunEnd(KernelState.empty(), CONTEXT.withWork(failed)));
        assertTrue(stop.reason().contains("timed out"));
    }

    @Test
    void absurdLineNumbersAreNotCitations() {
        assertSame(Allow.PLAIN, check(session("see Order.java:99999999999999999999 and Order.java:10")));
    }
}
