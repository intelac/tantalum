package tantalum.shell.mcp;

import java.util.Map;
import tantalum.shell.json.JsonText;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.ToolOutput;
import tantalum.value.json.Json;

/**
 * 一个 MCP 工具。恒为独占（默认的 {@code isConcurrencySafe}）：内核回放时会重新询问调度信息，
 * 而 MCP 工具的答案取决于回放那一刻的服务器和配置，并行会让回放的命令对不上。
 */
final class McpTool implements Tool {

    private final McpSettings.Server server;
    private final McpConnection connection;
    private final String publicName;
    private final String remoteName;
    private final String description;
    private final Json schema;

    McpTool(McpSettings.Server server, McpConnection connection, String publicName, String remoteName, String description,
            Json schema) {
        this.server = server;
        this.connection = connection;
        this.publicName = publicName;
        this.remoteName = remoteName;
        this.description = description;
        this.schema = schema;
    }

    @Override public String name() { return publicName; }
    @Override public String description() { return description; }
    @Override public Json schema() { return schema; }
    @Override public String server() { return "mcp:" + server.name(); }

    /** 配置里的服务器名，插件页按它开关。 */
    String serverName() { return server.name(); }
    @Override public int serverConcurrency() { return server.concurrency(); }

    @SuppressWarnings("unchecked")
    @Override
    public ToolOutput run(ToolContext context, Json arguments) throws InterruptedException {
        Object plain = arguments == null ? Map.of() : JsonText.toPlain(arguments);
        Map<String, Object> map = plain instanceof Map<?, ?> given ? (Map<String, Object>) given : Map.of();
        return connection.call(remoteName, map);
    }
}
