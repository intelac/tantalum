package tantalum.shell.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.shell.plugin.WorkResult;
import tantalum.shell.plugin.WorkType;
import tantalum.value.command.DoWork;
import tantalum.value.event.WorkDone;
import tantalum.value.id.CommandId;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;

class WorkHandlerTest {

    private static final RunRef RUN = new RunRef(new Principal("p"), new SessionId("s"), new RunId("r"));

    private static DoWork work(String type) {
        return new DoWork(new CommandId("w"), RUN, type, new Json.Str("input"));
    }

    private static WorkType type(String name, WorkResult result) {
        return new WorkType() {
            @Override public String name() { return name; }
            @Override public WorkResult run(tantalum.shell.plugin.ToolContext context, Json input) { return result; }
        };
    }

    @Test
    void dispatchesByType() throws Exception {
        WorkHandler handler = new WorkHandler(tantalum.shell.tool.Workspaces.shared(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"))), List.of(type("a", new WorkResult(new Json.Str("done"), "continue"))));
        WorkDone done = assertInstanceOf(WorkDone.class, handler.handle(work("a")));
        assertEquals(new Json.Str("done"), done.result());
        assertEquals("continue", done.continueText());
        assertEquals("a", done.workType());
        assertFalse(done.failed());
    }

    @Test
    void unknownTypeFails() throws Exception {
        WorkDone done = assertInstanceOf(WorkDone.class, new WorkHandler(tantalum.shell.tool.Workspaces.shared(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"))), List.of()).handle(work("nope")));
        assertTrue(done.failed());
    }

    @Test
    void failureBecomesAFailedResult() {
        WorkDone done = assertInstanceOf(WorkDone.class,
                new WorkHandler(tantalum.shell.tool.Workspaces.shared(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"))), List.of()).failed(work("a"), new IllegalStateException("boom")));
        assertTrue(done.failed());
        assertEquals(new Json.Str("java.lang.IllegalStateException: boom"), done.result());
    }

    @Test
    void duplicateTypeFails() {
        WorkType a = type("a", new WorkResult(new Json.Null(), null));
        assertThrows(IllegalStateException.class, () -> new WorkHandler(tantalum.shell.tool.Workspaces.shared(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"))), List.of(a, a)));
    }
}
