package tantalum.connect.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * 测试用的本机 MCP 服务器：一个 Java 程序，经 stdio 说 MCP（connector-jar 需求 9.1，CI 上不用 Node）。工具：
 * <ul>
 *   <li>{@code echo}：原样回 {@code text}，前面加工作目录的名字和环境变量 {@code MCP_TEST} 的值；</li>
 *   <li>{@code secret}：回 {@code GITHUB_TOKEN} 的值，看令牌一类的变量有没有传进来；</li>
 *   <li>{@code image}：回一张 1×1 的 PNG；</li>
 *   <li>{@code fail}：回错误结果；</li>
 *   <li>{@code child}：起一个睡很久的子进程，把 pid 写进 {@code child.pid}，测退出时整棵树都结束；</li>
 *   <li>{@code exit}：直接退出，测重连。</li>
 * </ul>
 * 启动时往 stderr 写一行，测 stderr 的收集；第一个参数是 {@code broken} 时启动就退出，测重连次数用完。
 */
public final class StdioTestServer {

    static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");

    private StdioTestServer() {}

    public static void main(String[] args) throws Exception {
        // stdout 只给 MCP：测试的 classpath 上有日志库，默认写 stdout，会把协议弄乱。真的 MCP 服务器也不能往 stdout 写别的
        java.io.PrintStream protocol = System.out;
        System.setOut(System.err);
        System.err.println("test server starting in " + Path.of("").toAbsolutePath().getFileName());
        if (args.length > 0 && args[0].equals("broken")) {
            System.err.println("broken on purpose");
            System.exit(3);
        }
        StdioServerTransportProvider provider = new StdioServerTransportProvider(McpJsonDefaults.getMapper(), System.in, protocol);
        List<McpServerFeatures.SyncToolSpecification> tools = new ArrayList<>();
        tools.add(tool("echo", "text", arguments -> text(Path.of("").toAbsolutePath().getFileName() + "|" + System.getenv("MCP_TEST") + "|"
                + arguments.get("text"))));
        tools.add(tool("secret", null, arguments -> text(String.valueOf(System.getenv("GITHUB_TOKEN")))));
        tools.add(tool("image", null, arguments -> McpSchema.CallToolResult.builder()
                .addContent(new McpSchema.ImageContent(null, Base64.getEncoder().encodeToString(PNG), "image/png"))
                .addTextContent("one pixel").isError(false).build()));
        tools.add(tool("fail", null, arguments -> McpSchema.CallToolResult.builder().addTextContent("it failed").isError(true).build()));
        tools.add(tool("child", null, arguments -> {
            try {
                String java = ProcessHandle.current().info().command().orElse("java");
                Process child = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), Sleeper.class.getName())
                        .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                Files.writeString(Path.of("child.pid"), Long.toString(child.pid()));
                return text("started " + child.pid());
            } catch (Exception failure) {
                return text("could not start: " + failure);
            }
        }));
        tools.add(tool("exit", null, arguments -> {
            System.exit(1);
            return text("unreachable");
        }));
        McpServer.sync(provider).serverInfo("stdio-test", "1")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .tools(tools).build();
        // 传输在自己的线程里读 stdin；主线程等着，stdin 关了进程就结束
        Thread.currentThread().join();
    }

    private static McpServerFeatures.SyncToolSpecification tool(String name, String param,
                                                                 java.util.function.Function<Map<String, Object>, McpSchema.CallToolResult> handler) {
        McpSchema.Tool tool = McpSchema.Tool.builder().name(name).description("test tool " + name)
                .inputSchema(param == null ? new McpSchema.JsonSchema("object", Map.of(), List.of(), true, null, null)
                        : new McpSchema.JsonSchema("object", Map.of(param, Map.of("type", "string")), List.of(param), true, null, null))
                .build();
        return McpServerFeatures.SyncToolSpecification.builder().tool(tool)
                .callHandler((exchange, request) -> handler.apply(request.arguments())).build();
    }

    private static McpSchema.CallToolResult text(String text) {
        return McpSchema.CallToolResult.builder().addTextContent(text).isError(false).build();
    }

    /** 睡很久的子进程。 */
    public static final class Sleeper {
        public static void main(String[] args) throws InterruptedException {
            Thread.sleep(600_000);
        }
    }
}
