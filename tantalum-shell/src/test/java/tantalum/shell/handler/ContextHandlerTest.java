package tantalum.shell.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.kernel.projection.PromptProjection;
import tantalum.shell.memory.KnowledgeBase;
import tantalum.shell.memory.MemoryGuidance;
import tantalum.shell.memory.MemoryLimits;
import tantalum.shell.memory.MemoryStores;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.ToolOutput;
import tantalum.shell.tool.ToolCatalog;
import tantalum.shell.tool.Workspaces;
import tantalum.value.command.AssembleContext;
import tantalum.value.event.CommandDropped;
import tantalum.value.event.ContextAssembled;
import tantalum.value.hook.PromptPatch;
import tantalum.value.id.CommandId;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;
import tantalum.value.model.Prompt;
import tantalum.value.state.OwnerRef;
import tantalum.value.state.RunState;
import tantalum.value.state.SessionState;

class ContextHandlerTest {

    private static final Principal ALICE = new Principal("alice");
    private static final Principal BOB = new Principal("bob");
    private static final AssembleContext COMMAND = command(ALICE);

    private static AssembleContext command(Principal principal) {
        return new AssembleContext(new CommandId("c"), new RunRef(principal, new SessionId("s"), new RunId("r")));
    }

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

    private MemoryStores memories(Workspaces workspaces) {
        return new MemoryStores(workspaces, dir.resolve("data"), MemoryLimits.defaults(), Clock.systemUTC());
    }

    private ContextHandler handler(Workspaces workspaces, KnowledgeBase knowledge) {
        return new ContextHandler(workspaces, "Base prompt.", catalog(), memories(workspaces), knowledge);
    }

    private ContextHandler shared() {
        return handler(Workspaces.shared(dir), KnowledgeBase.none());
    }

    private static ContextAssembled assembled(ContextHandler handler, AssembleContext command) throws Exception {
        return assertInstanceOf(ContextAssembled.class, handler.handle(command));
    }

    @Test
    void systemPromptIncludesAgentsFileAndToolsAreListed() throws Exception {
        Files.writeString(dir.resolve("AGENTS.md"), "Project rules here.");
        ContextAssembled event = assembled(shared(), COMMAND);
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
        assertFalse(assembled(shared(), COMMAND).system().contains("TOP SECRET"));
    }

    @Test
    void withoutAgentsKnowledgeOrMemoriesSystemIsBaseAndGuidanceOnly() throws Exception {
        String system = assembled(shared(), COMMAND).system();
        assertEquals("Base prompt.\n\n" + MemoryGuidance.TEXT.strip(), system);
        assertFalse(system.contains(ContextHandler.INDEX_HEADING));
        assertFalse(system.contains(ContextHandler.KNOWLEDGE_HEADING));
    }

    @Test
    void sectionsAppearInTheFixedOrderOnlyWhenPresent(@TempDir Path knowledgeDir) throws Exception {
        Files.writeString(knowledgeDir.resolve("team.md"), "Team facts.");
        Workspaces workspaces = Workspaces.perUser(dir.resolve("ws"));
        MemoryStores memories = memories(workspaces);
        Files.writeString(workspaces.of(ALICE).root().resolve("AGENTS.md"), "Agents rules.");
        memories.of(ALICE).write("java", "user", "Java 25 only", "body");
        ContextHandler handler = new ContextHandler(workspaces, "Base.", catalog(), memories,
                new KnowledgeBase(Optional.of(knowledgeDir), 64 * 1024));

        String system = assembled(handler, command(ALICE)).system();

        int base = system.indexOf("Base.");
        int guidance = system.indexOf("## Memory");
        int knowledge = system.indexOf(ContextHandler.KNOWLEDGE_HEADING + "\n\n### team.md\n\nTeam facts.");
        int agents = system.indexOf("Agents rules.");
        int index = system.indexOf(ContextHandler.INDEX_HEADING + "\n\n- [java](java.md) — user — Java 25 only");
        assertTrue(base == 0 && base < guidance && guidance < knowledge && knowledge < agents && agents < index, system);
        assertTrue(system.endsWith("Java 25 only"), "index is the last section");
    }

    @Test
    void eachUserSeesOnlyTheirOwnIndex() throws Exception {
        Workspaces workspaces = Workspaces.perUser(dir.resolve("ws"));
        MemoryStores memories = memories(workspaces);
        memories.of(ALICE).write("alice-fact", "user", "alice likes tabs", "x");
        memories.of(BOB).write("bob-fact", "user", "bob likes spaces", "x");
        ContextHandler handler = new ContextHandler(workspaces, "Base.", catalog(), memories, KnowledgeBase.none());

        String alice = assembled(handler, command(ALICE)).system();
        String bob = assembled(handler, command(BOB)).system();
        assertTrue(alice.contains("alice likes tabs") && !alice.contains("bob likes spaces"), alice);
        assertTrue(bob.contains("bob likes spaces") && !bob.contains("alice likes tabs"), bob);
    }

    /** 钩子追加的 system 文字落在索引之后：system 的前缀在整个运行里不变，缓存断点才稳定。 */
    @Test
    void hookAppendsLandAfterTheIndex() throws Exception {
        Workspaces workspaces = Workspaces.perUser(dir.resolve("ws"));
        MemoryStores memories = memories(workspaces);
        memories.of(ALICE).write("java", "user", "Java 25 only", "body");
        ContextHandler handler = new ContextHandler(workspaces, "Base.", catalog(), memories, KnowledgeBase.none());
        ContextAssembled event = assembled(handler, command(ALICE));

        RunState run = RunState.start(command(ALICE).run(), new OwnerRef.OfSession(new SessionId("s")))
                .withContext(event.system(), event.tools());
        Prompt prompt = PromptProjection.project(run, SessionState.start(new SessionId("s"), ALICE),
                new PromptPatch("\n\n## Quota\nhalf used", List.of()));
        assertTrue(prompt.system().indexOf("Java 25 only") < prompt.system().indexOf("## Quota"));
        assertTrue(prompt.system().startsWith(event.system()));
    }

    @Test
    void missingAgentsFileIsFine() throws Exception {
        assertTrue(assembled(shared(), COMMAND).system().startsWith("Base prompt."));
    }

    @Test
    void failureBecomesADroppedCommand() {
        CommandDropped dropped = assertInstanceOf(CommandDropped.class,
                shared().failed(COMMAND, new java.io.IOException("disk")));
        assertTrue(dropped.detail().contains("disk"));
    }
}
