package tantalum.shell.handler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import tantalum.shell.memory.KnowledgeBase;
import tantalum.shell.memory.MemoryGuidance;
import tantalum.shell.memory.MemoryStores;
import tantalum.shell.plugin.Workspace;
import tantalum.shell.tool.ToolCatalog;
import tantalum.shell.tool.Workspaces;
import tantalum.value.command.Command;
import tantalum.value.event.CommandDropped;
import tantalum.value.event.ContextAssembled;
import tantalum.value.event.Event;
import tantalum.value.id.Principal;

/**
 * 组装 system prompt 和工具列表，完整写进事件，回放时不再读文件。
 * system 的顺序固定：基础提示 → 记忆指引 → 共享知识 → AGENTS.md → 该用户的记忆索引；每段只在有内容时加入。
 * 索引放在最后，钩子追加的内容在它之后，所以 prompt caching 的 system 断点整个运行里稳定。
 */
public final class ContextHandler implements Handler {

    static final String KNOWLEDGE_HEADING = "## Shared knowledge";
    static final String INDEX_HEADING = "## Your memory index";

    private final Workspaces workspaces;
    private final String basePrompt;
    private final ToolCatalog catalog;
    private final MemoryStores memories;
    private final KnowledgeBase knowledge;

    public ContextHandler(Workspaces workspaces, String basePrompt, ToolCatalog catalog, MemoryStores memories,
                          KnowledgeBase knowledge) {
        this.workspaces = workspaces;
        this.basePrompt = basePrompt;
        this.catalog = catalog;
        this.memories = memories;
        this.knowledge = knowledge;
    }

    @Override
    public Event handle(Command command) throws IOException {
        Principal principal = command.run().principal();
        StringBuilder system = new StringBuilder(basePrompt);
        append(system, MemoryGuidance.TEXT.strip());
        String shared = knowledge.text();
        if (!shared.isEmpty()) {
            append(system, KNOWLEDGE_HEADING + "\n\n" + shared);
        }
        Path agents = agentsFile(workspaces.of(principal));
        if (agents != null && Files.isRegularFile(agents)) {
            append(system, Files.readString(agents).strip());
        }
        String index = memories.of(principal).indexText().strip();
        if (!index.isEmpty()) {
            append(system, INDEX_HEADING + "\n\n" + index);
        }
        return new ContextAssembled(command.id(), system.toString(), catalog.specs());
    }

    private static void append(StringBuilder system, String section) {
        if (section.isEmpty()) {
            return;
        }
        if (!system.isEmpty()) {
            system.append("\n\n");
        }
        system.append(section);
    }

    /** {@code AGENTS.md} 也走 {@code Workspace.resolve}：指向目录外的符号链接不会被读进 prompt。 */
    private static Path agentsFile(Workspace workspace) {
        try {
            return workspace.resolve("AGENTS.md");
        } catch (IllegalArgumentException missingOrOutside) {
            return null;
        }
    }

    @Override
    public Event failed(Command command, Exception failure) {
        return new CommandDropped(command.id(), "cannot assemble context: " + failure.getMessage());
    }
}
