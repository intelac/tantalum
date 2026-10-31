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
        WorkDone done = new WorkDone(new CommandId("c"), CitationWork.TYPE, new Json.Str("ok"), false, null);
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

    @Test
    void citationOutsideTheWorkspaceIsRejected() throws Exception {
        WorkResult result = new CitationWork().run(new tantalum.shell.plugin.ToolContext(USER, new tantalum.shell.plugin.Workspace(dir)), new Json.Str("see ../../etc/hosts.txt:1"));
        assertTrue(result.continueText().contains("hosts.txt:1"));
    }
}
