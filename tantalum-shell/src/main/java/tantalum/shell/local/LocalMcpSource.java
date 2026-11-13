package tantalum.shell.local;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import tantalum.local.wire.McpServerState;
import tantalum.local.wire.McpServerState.State;
import tantalum.shell.attachment.ToolImages;
import tantalum.shell.plugin.Tool;
import tantalum.shell.tool.ToolCatalog;
import tantalum.value.id.Principal;

/**
 * 每个用户电脑上的本机 MCP 工具（connector-jar 需求 6.3）：取这个用户现在连着的电脑最新报的清单（{@code hello.mcp}，随
 * {@code mcpTools} 更新），能用的和正在重连的服务器的工具都算（重连期间工具保留，清单不抖动）。电脑没连上就没有。
 * 开关（本机工作区总开关、“本机 MCP”、每个服务器）由 {@link LocalWorkspace#enabled} 管，这里只列出来。
 */
public final class LocalMcpSource implements ToolCatalog.UserToolSource {

    private final LocalLinks links;
    private final ToolImages images;

    public LocalMcpSource(LocalLinks links, ToolImages images) {
        this.links = links;
        this.images = images;
    }

    @Override
    public List<Tool> tools(Principal user) {
        List<Tool> tools = new ArrayList<>();
        for (McpServerState server : servers(user)) {
            if (server.state() == State.READY || server.state() == State.STARTING) {
                server.tools().forEach(spec -> tools.add(new LocalMcpTool(server.name(), spec, links, images)));
            }
        }
        return tools;
    }

    @Override
    public Optional<Tool> find(Principal user, String name) {
        return tools(user).stream().filter(tool -> tool.name().equals(name)).findFirst();
    }

    /** 这个用户的电脑报的本机 MCP 服务器；没连上或老的连接程序是空的。 */
    public List<McpServerState> servers(Principal user) {
        return links.status(user).map(status -> status.hello().mcp()).orElse(List.of());
    }
}
