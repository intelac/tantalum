package tantalum.shell.handler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import tantalum.shell.plugin.Workspace;
import tantalum.shell.tool.ToolCatalog;
import tantalum.shell.tool.Workspaces;
import tantalum.value.command.Command;
import tantalum.value.event.CommandDropped;
import tantalum.value.event.ContextAssembled;
import tantalum.value.event.Event;

/** 组装 system prompt 和工具列表，完整写进事件，回放时不再读文件。 */
public final class ContextHandler implements Handler {

    private final Workspaces workspaces;
    private final String basePrompt;
    private final ToolCatalog catalog;

    public ContextHandler(Workspaces workspaces, String basePrompt, ToolCatalog catalog) {
        this.workspaces = workspaces;
        this.basePrompt = basePrompt;
        this.catalog = catalog;
    }

    /** {@code AGENTS.md} 也走 {@code Workspace.resolve}：指向目录外的符号链接不会被读进 prompt。 */
    @Override
    public Event handle(Command command) throws IOException {
        String system = basePrompt;
        Path agents = agentsFile(workspaces.of(command.run().principal()));
        if (agents != null && Files.isRegularFile(agents)) {
            system = basePrompt + "\n\n" + Files.readString(agents);
        }
        return new ContextAssembled(command.id(), system, catalog.specs());
    }

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
