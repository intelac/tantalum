package tantalum.shell.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 构造 MCP 客户端的唯一地方。不跟随重定向，否则凭据可能被带到别的主机。 */
final class McpClients {

    private static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private static final Logger LOG = LoggerFactory.getLogger(McpClients.class);
    static final int MAX_PAGES = 100;

    private McpClients() {}

    static McpSyncClient client(McpSettings.Server server, Consumer<List<McpSchema.Tool>> onToolsChanged) {
        URI url = server.url();
        String base = url.getScheme() + "://" + url.getRawAuthority();
        String endpoint = (url.getRawPath() == null || url.getRawPath().isEmpty() ? "/" : url.getRawPath())
                + (url.getRawQuery() == null ? "" : "?" + url.getRawQuery());
        String token = server.token();
        var transport = HttpClientStreamableHttpTransport.builder(base).endpoint(endpoint)
                .jsonMapper(McpJsonDefaults.getMapper())
                .customizeClient(client -> client.followRedirects(HttpClient.Redirect.NEVER))
                .connectTimeout(CONNECT_TIMEOUT)
                .maxResponseSize(MAX_RESPONSE_BYTES)
                .httpRequestCustomizer((request, method, uri, body, context) -> {
                    if (token != null) {
                        request.header("Authorization", "Bearer " + token);
                    }
                }).build();
        return McpClient.sync(transport).clientInfo(new McpSchema.Implementation("tantalum", "0.1.0"))
                .requestTimeout(server.timeout()).initializationTimeout(server.timeout())
                .toolsChangeConsumer(onToolsChanged).build();
    }

    /** 列出全部工具，跟随分页。 */
    static List<McpSchema.Tool> listAll(McpSyncClient client) {
        return collectPages(client.listTools(), client::listTools);
    }

    /** 分页有上限：有缺陷或恶意的服务器可能一直给下一页游标。超限时保留已取到的。 */
    static List<McpSchema.Tool> collectPages(McpSchema.ListToolsResult first, Function<String, McpSchema.ListToolsResult> next) {
        List<McpSchema.Tool> tools = new ArrayList<>(first.tools());
        McpSchema.ListToolsResult page = first;
        int pages = 1;
        while (page.nextCursor() != null && !page.nextCursor().isEmpty()) {
            if (pages >= MAX_PAGES) {
                LOG.warn("mcp tool list exceeds {} pages; keeping the first ones", MAX_PAGES);
                break;
            }
            page = next.apply(page.nextCursor());
            tools.addAll(page.tools());
            pages++;
        }
        return List.copyOf(tools);
    }
}
