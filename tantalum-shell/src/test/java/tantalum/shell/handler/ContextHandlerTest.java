package tantalum.shell.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.ToolOutput;
import tantalum.shell.tool.ToolCatalog;
import tantalum.value.command.AssembleContext;
import tantalum.value.event.CommandDropped;
import tantalum.value.event.ContextAssembled;
import tantalum.value.id.CommandId;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;

class ContextHandlerTest {

    private static final AssembleContext COMMAND = new AssembleContext(new CommandId("c"),
            new RunRef(new Principal("p"), new SessionId("s"), new RunId("r")));

    @TempDir
    Path dir;

    private ToolCatalog catalog() {
        ToolCatalog catalog = new ToolCatalog();
        catalog.register(new Tool() {
            @Override public String name() { return "grep"; }
            @Override public String description() { return "d"; }
            @Override public Json schema() { return new Json.Null(); }
            @Override public ToolOutput run(tantalum.shell.plugin.ToolContext context, Json arguments) { return ToolOutput.ok(""); }
        });
        return catalog;
    }

    @Test
    void systemPromptIncludesAgentsFileAndToolsAreListed() throws Exception {
        Files.writeString(dir.resolve("AGENTS.md"), "Project rules here.");
        ContextAssembled event = assertInstanceOf(ContextAssembled.class,
                new ContextHandler(tantalum.shell.tool.Workspaces.shared(dir), "Base prompt.", catalog()).handle(COMMAND));
        assertTrue(event.system().startsWith("Base prompt."));
        assertTrue(event.system().contains("Project rules here."));
        assertEquals("grep", event.tools().getFirst().name());
        assertEquals(COMMAND.id(), event.command());
    }

    /** AGENTS.md 若是指向工作目录外的符号链接，不能把外面的文件读进 prompt。 */
    @Test
    void agentsFileLinkingOutsideTheWorkspaceIsIgnored(@TempDir Path elsewhere) throws Exception {
        Path secret = elsewhere.resolve("secret.md");
        Files.writeString(secret, "TOP SECRET");
        Files.createSymbolicLink(dir.resolve("AGENTS.md"), secret);
        ContextAssembled event = assertInstanceOf(ContextAssembled.class,
                new ContextHandler(tantalum.shell.tool.Workspaces.shared(dir), "Base prompt.", catalog()).handle(COMMAND));
        assertEquals("Base prompt.", event.system());
    }

    @Test
    void missingAgentsFileIsFine() throws Exception {
        ContextAssembled event = assertInstanceOf(ContextAssembled.class,
                new ContextHandler(tantalum.shell.tool.Workspaces.shared(dir), "Base prompt.", catalog()).handle(COMMAND));
        assertEquals("Base prompt.", event.system());
    }

    @Test
    void failureEndsTheRunThroughCommandDropped() {
        CommandDropped event = assertInstanceOf(CommandDropped.class,
                new ContextHandler(tantalum.shell.tool.Workspaces.shared(dir), "", catalog()).failed(COMMAND, new java.io.IOException("disk")));
        assertTrue(event.detail().contains("disk"));
    }
}
