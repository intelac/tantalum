package tantalum.shell.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;

/** 进程内的真实 MCP 服务器（官方 SDK + 内嵌 Tomcat）。记录收到的凭据和调用，可停可启，可拒绝访问。不访问外网。 */
public final class TestMcpServer implements AutoCloseable {

    /** 工具处理函数：参数到结果。 */
    public record Definition(String name, Boolean readOnly, String param, Function<Map<String, Object>, McpSchema.CallToolResult> handler) {
        public Definition(String name, Boolean readOnly, Function<Map<String, Object>, McpSchema.CallToolResult> handler) {
            this(name, readOnly, null, handler);
        }
    }

    public final List<String> calls = new CopyOnWriteArrayList<>();
    public final Set<String> authorizations = ConcurrentHashMap.newKeySet();
    public volatile boolean rejectAll;
    /** 接下来这么多个 POST 直接返回 500，模拟一次调用的服务端错误。 */
    public final java.util.concurrent.atomic.AtomicInteger failNextPosts = new java.util.concurrent.atomic.AtomicInteger();
    /** 重启时不声明 tools 能力。 */
    public volatile boolean withoutTools;

    private final List<Definition> definitions = new CopyOnWriteArrayList<>();
    private McpSyncServer server;
    private Tomcat tomcat;
    private int port;

    public TestMcpServer(Definition... tools) throws Exception {
        definitions.addAll(List.of(tools));
        start();
    }

    public static McpSchema.CallToolResult text(String text) {
        return McpSchema.CallToolResult.builder().addTextContent(text).isError(false).build();
    }

    public static McpSchema.CallToolResult error(String text) {
        return McpSchema.CallToolResult.builder().addTextContent(text).isError(true).build();
    }

    private McpServerFeatures.SyncToolSpecification spec(Definition definition) {
        McpSchema.Tool tool = McpSchema.Tool.builder().name(definition.name()).description("test tool " + definition.name())
                .inputSchema(definition.param() == null ? new McpSchema.JsonSchema("object", Map.of(), List.of(), true, null, null)
                        : new McpSchema.JsonSchema("object", Map.of(definition.param(), Map.of("type", "string")), List.of(definition.param()), true, null, null))
                .annotations(new McpSchema.ToolAnnotations(definition.name(), definition.readOnly(), null, null, null, null)).build();
        return McpServerFeatures.SyncToolSpecification.builder().tool(tool).callHandler((exchange, request) -> {
            calls.add(definition.name());
            return definition.handler().apply(request.arguments());
        }).build();
    }

    public void start() throws Exception {
        var provider = HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(McpJsonDefaults.getMapper()).mcpEndpoint("/mcp").build();
        server = withoutTools
                ? McpServer.sync(provider).serverInfo("test", "1").capabilities(McpSchema.ServerCapabilities.builder().build()).build()
                : McpServer.sync(provider).serverInfo("test", "1")
                        .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                        .tools(definitions.stream().map(this::spec).toList()).build();
        tomcat = new Tomcat();
        tomcat.setPort(port);
        tomcat.setBaseDir(Files.createTempDirectory("mcp-tomcat").toString());
        tomcat.getConnector();
        Context context = tomcat.addContext("", null);
        Tomcat.addServlet(context, "mcp", provider).setAsyncSupported(true);
        context.addServletMappingDecoded("/mcp", "mcp");
        Tomcat.addServlet(context, "redirect", new HttpServlet() {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) {
                response.setStatus(302);
                response.setHeader("Location", "/mcp");
            }
        }).setAsyncSupported(true);
        context.addServletMappingDecoded("/redirect", "redirect");
        FilterDef filter = new FilterDef();
        filter.setFilterName("auth");
        filter.setAsyncSupported("true");
        filter.setFilter(new HttpFilter() {
            @Override
            protected void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws IOException, ServletException {
                String authorization = request.getHeader("Authorization");
                if (authorization != null) {
                    authorizations.add(authorization);
                }
                if (rejectAll) {
                    response.setStatus(401);
                    return;
                }
                if ("POST".equals(request.getMethod()) && failNextPosts.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
                    response.setStatus(500);
                    return;
                }
                chain.doFilter(request, response);
            }
        });
        context.addFilterDef(filter);
        FilterMap map = new FilterMap();
        map.setFilterName("auth");
        map.addURLPattern("/*");
        context.addFilterMap(map);
        tomcat.start();
        port = tomcat.getConnector().getLocalPort();
    }

    /** 可重复调用：测试里可能先手动停掉再由 try-with-resources 关闭。 */
    public void stop() throws Exception {
        if (tomcat == null) {
            return;
        }
        server.closeGracefully();
        tomcat.stop();
        tomcat.destroy();
        tomcat = null;
    }

    public void addTool(Definition definition) {
        definitions.add(definition);
        server.addTool(spec(definition));
        server.notifyToolsListChanged();
    }

    public String url() {
        return "http://127.0.0.1:" + port + "/mcp";
    }

    public String redirectUrl() {
        return "http://127.0.0.1:" + port + "/redirect";
    }

    @Override
    public void close() throws Exception {
        stop();
    }
}
