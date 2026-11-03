package tantalum.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.plugin.WorkResult;
import tantalum.value.event.WorkDone;
import tantalum.value.hook.Allow;
import tantalum.value.hook.HookContext;
import tantalum.value.hook.NeedWork;
import tantalum.value.id.CommandId;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;
import tantalum.value.model.Message;
import tantalum.value.model.Role;
import tantalum.value.model.TextBlock;
import tantalum.value.state.KernelState;
import tantalum.value.state.SessionState;

class CitationCheckTest {

    private static final Principal USER = new Principal("u");
    private static final SessionId SESSION = new SessionId("s");
    private static final HookContext CONTEXT = HookContext.of(new RunRef(USER, SESSION, new RunId("r")), Instant.EPOCH);

    @TempDir
    Path dir;

    @Test
    void hookAsksForWorkWithTheLastAnswer() {
        KernelState state = KernelState.empty().with(SessionState.start(SESSION, USER)
                .append(new Message(Role.ASSISTANT, List.of(new TextBlock("old")), false))
                .append(Message.user("again"))
                .append(new Message(Role.ASSISTANT, List.of(new TextBlock("see A.cbl:1")), false)));
        NeedWork need = assertInstanceOf(NeedWork.class, new CitationCheckHook().beforeRunEnd(state, CONTEXT));
        assertEquals(CitationWork.TYPE, need.workType());
        assertEquals(new Json.Str("see A.cbl:1"), need.input());
    }

    @Test
    void hookAllowsOnceTheWorkIsDone() {
        WorkDone done = new WorkDone(new CommandId("c"), CitationWork.TYPE, new Json.Str("ok"), false, null, null);
        assertSame(Allow.PLAIN, new CitationCheckHook().beforeRunEnd(KernelState.empty(), CONTEXT.withWork(done)));
    }

    @Test
    void validCitationPasses() throws Exception {
        Files.writeString(dir.resolve("A.cbl"), "one\ntwo\n");
        WorkResult result = new CitationWork().run(new tantalum.shell.plugin.ToolContext(USER, new tantalum.shell.plugin.Workspace(dir)), new Json.Str("It is in A.cbl:2 clearly"));
        assertNull(result.continueText());
    }

    @Test
    void answerWithoutCitationsPasses() throws Exception {
        assertNull(new CitationWork().run(new tantalum.shell.plugin.ToolContext(USER, new tantalum.shell.plugin.Workspace(dir)), new Json.Str("no references here")).continueText());
    }

    @Test
    void wrongLineOrMissingFileGoesBackToTheModel() throws Exception {
        Files.writeString(dir.resolve("A.cbl"), "one\n");
        WorkResult result = new CitationWork().run(new tantalum.shell.plugin.ToolContext(USER, new tantalum.shell.plugin.Workspace(dir)), new Json.Str("see A.cbl:9 and B.cbl:1"));
        assertTrue(result.continueText().contains("A.cbl:9"));
        assertTrue(result.continueText().contains("B.cbl:1"));
    }

    /** 核对本身失败（文件读不了、工作超时）不能当作通过。 */
    @Test
    void failedWorkStopsTheRunInsteadOfPassing() {
        WorkDone failed = new WorkDone(new CommandId("c"), CitationWork.TYPE, new Json.Str("work timed out"), true, null, null);
        tantalum.value.hook.Stop stop = assertInstanceOf(tantalum.value.hook.Stop.class,
                new CitationCheckHook().beforeRunEnd(KernelState.empty(), CONTEXT.withWork(failed)));
        assertTrue(stop.reason().contains("timed out"));
    }

    /** 遗留 COBOL 源常见的非 UTF-8 编码：按字节数行，不抛异常。 */
    @Test
    void nonUtf8FileIsCountedByBytes() throws Exception {
        Files.write(dir.resolve("LEGACY.cbl"), new byte[] {'A', (byte) 0xC9, '\n', 'B', (byte) 0xFF, '\n', 'C'});
        var context = new tantalum.shell.plugin.ToolContext(USER, new tantalum.shell.plugin.Workspace(dir));
        assertNull(new CitationWork().run(context, new Json.Str("see LEGACY.cbl:3")).continueText());
        assertTrue(new CitationWork().run(context, new Json.Str("see LEGACY.cbl:4")).continueText().contains("LEGACY.cbl:4"));
    }

    @Test
    void absurdLineNumbersDoNotCrashTheCheck() throws Exception {
        Files.writeString(dir.resolve("A.cbl"), "one\n");
        WorkResult result = new CitationWork().run(new tantalum.shell.plugin.ToolContext(USER, new tantalum.shell.plugin.Workspace(dir)),
                new Json.Str("see A.cbl:99999999999999999999 and A.cbl:1"));
        assertNull(result.continueText(), "the 20-digit number is not a line citation; A.cbl:1 is valid");
    }

    @Test
    void citationOutsideTheWorkspaceIsRejected() throws Exception {
        WorkResult result = new CitationWork().run(new tantalum.shell.plugin.ToolContext(USER, new tantalum.shell.plugin.Workspace(dir)), new Json.Str("see ../../etc/hosts.txt:1"));
        assertTrue(result.continueText().contains("hosts.txt:1"));
    }
}
