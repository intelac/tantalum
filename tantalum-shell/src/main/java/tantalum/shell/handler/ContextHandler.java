package tantalum.shell.handler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    @Override
    public Event handle(Command command) throws IOException {
        String system = basePrompt;
        Path agents = workspaces.of(command.run().principal()).root().resolve("AGENTS.md");
        if (Files.isRegularFile(agents)) {
            system = basePrompt + "\n\n" + Files.readString(agents);
        }
        return new ContextAssembled(command.id(), system, catalog.specs());
    }

    @Override
    public Event failed(Command command, Exception failure) {
        return new CommandDropped(command.id(), "cannot assemble context: " + failure.getMessage());
    }
}
