package tantalum.connect.browser;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import tantalum.local.tool.LocalImage;
import tantalum.local.tool.LocalResult;
import tantalum.local.wire.Wire;
import tantalum.value.json.Json;
import tools.jackson.databind.json.JsonMapper;

/**
 * Playwright MCP 子进程和连着它的客户端（connector-jar 需求 7；原来是本机 MCP 的通用代码，remove-generic-mcp 后只给浏览器用）。
 * <ul>
 *   <li>官方 Java SDK 的 stdio 传输；子类改 {@code getProcessBuilder()}：工作目录、只用给它的环境变量（{@link tantalum.local.shell.CommandEnv}）。</li>
 *   <li>Windows 上命令不是 {@code .exe} 时经 {@code cmd.exe /d /s /c} 启动：{@code npx} 实际是 {@code npx.cmd}，Java 直接起不了
 *       （pi 用 {@code cross-spawn} 解决同一个问题，{@code pi-mcp/dist/transports/stdio.js:3}）。</li>
 *   <li>stderr 留最后 64 KB（pi {@code stdio.js:6,194-197}），出错时末尾 2000 字符随原因。</li>
 *   <li>SDK 关闭时只结束直接子进程，{@code npx} 起的 {@code node} 会留下：启动时记下子进程，关闭时先结束整棵树。</li>
 * </ul>
 */
final class PlaywrightProcess implements AutoCloseable {

    static final Duration INIT_TIMEOUT = Duration.ofSeconds(30);
    static final int STDERR_BYTES = 64 * 1024;
    static final int REASON_CHARS = 2000;
    /** 一次结果里文字的上限：服务器那边还会再截。 */
    static final int MAX_TEXT_CHARS = 1_000_000;
    /** 同一时刻只启动一个：靠前后对比本进程的子进程认出新起的那个。 */
    private static final Object STARTING = new Object();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ServerCommand command;
    private final StringBuilder stderr = new StringBuilder();
    private McpSyncClient client;
    private volatile ProcessHandle process;
    private List<McpSchema.Tool> tools = List.of();

    PlaywrightProcess(ServerCommand command) {
        this.command = command;
    }

    /** 起进程、初始化、列工具。失败时抛异常，原因带 stderr 的末尾；已经起来的进程结束掉。 */
    void start(Map<String, String> env, Path cwd, Duration callTimeout) {
        List<String> line = commandLine(command.command(), command.args(), System.getProperty("os.name", ""));
        ServerParameters parameters = ServerParameters.builder(line.getFirst()).args(line.subList(1, line.size())).env(env).build();
        StdioClientTransport transport = new StdioClientTransport(parameters, McpJsonDefaults.getMapper()) {
            @Override
            protected ProcessBuilder getProcessBuilder() {
                ProcessBuilder builder = new ProcessBuilder().directory(cwd.toFile());
                builder.environment().clear();
                return builder;
            }
        };
        transport.setStdErrorHandler(this::stderr);
        client = McpClient.sync(transport).clientInfo(new McpSchema.Implementation("tantalum-connect", "0.1.0"))
                .requestTimeout(callTimeout).initializationTimeout(INIT_TIMEOUT).build();
        // 初始化放到别的线程：这边认出新起的子进程，它在初始化完成前就退出了（命令写错、启动就崩）不用等满超时
        java.util.concurrent.CompletableFuture<Void> init;
        synchronized (STARTING) {
            Set<Long> before = children();
            init = java.util.concurrent.CompletableFuture.runAsync(client::initialize, runnable -> Thread.ofVirtual().start(runnable));
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (process == null && !init.isDone() && System.nanoTime() < deadline) {
                process = ProcessHandle.current().children().filter(child -> !before.contains(child.pid())).findFirst().orElse(null);
                if (process == null) {
                    sleep(20);
                }
            }
        }
        long deadline = System.nanoTime() + INIT_TIMEOUT.toNanos();
        while (!init.isDone()) {
            if (process != null && !process.isAlive()) {
                sleep(200);
                init.cancel(true);
                throw new IllegalStateException("Playwright MCP exited while starting");
            }
            if (System.nanoTime() > deadline) {
                init.cancel(true);
                throw new IllegalStateException("Playwright MCP did not finish starting within " + INIT_TIMEOUT.toSeconds() + " seconds");
            }
            sleep(50);
        }
        try {
            init.join();
        } catch (java.util.concurrent.CompletionException failure) {
            throw failure.getCause() instanceof RuntimeException runtime ? runtime : failure;
        }
        tools = listAll();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while starting Playwright MCP");
        }
    }

    private static Set<Long> children() {
        Set<Long> pids = new HashSet<>();
        ProcessHandle.current().children().forEach(child -> pids.add(child.pid()));
        return pids;
    }

    /** Windows 上不是 {@code .exe} 的命令（{@code npx}、{@code .cmd}、{@code .bat}）经 {@code cmd.exe} 起。 */
    static List<String> commandLine(String command, List<String> args, String osName) {
        List<String> line = new ArrayList<>();
        boolean windows = osName.toLowerCase(Locale.ROOT).startsWith("windows");
        if (windows && !command.toLowerCase(Locale.ROOT).endsWith(".exe")) {
            line.addAll(List.of("cmd.exe", "/d", "/s", "/c"));
        }
        line.add(command);
        line.addAll(args);
        return line;
    }

    List<McpSchema.Tool> listAll() {
        List<McpSchema.Tool> all = new ArrayList<>();
        McpSchema.ListToolsResult page = client.listTools();
        all.addAll(page.tools());
        for (int pages = 1; page.nextCursor() != null && !page.nextCursor().isEmpty() && pages < 100; pages++) {
            page = client.listTools(page.nextCursor());
            all.addAll(page.tools());
        }
        tools = List.copyOf(all);
        return tools;
    }

    List<McpSchema.Tool> tools() {
        return tools;
    }

    boolean alive() {
        return process == null || process.isAlive();
    }

    /** 调一个工具：文字连起来，图片原样送回，别的内容换成一行说明；{@code isError} 是错误结果。 */
    LocalResult call(String tool, Json arguments) {
        @SuppressWarnings("unchecked")
        Map<String, Object> args = arguments instanceof Json.Obj ? JSON.readValue(Wire.text(arguments), Map.class) : Map.of();
        McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(tool, args));
        return toResult(result);
    }

    static LocalResult toResult(McpSchema.CallToolResult result) {
        StringBuilder text = new StringBuilder();
        List<LocalImage> images = new ArrayList<>();
        for (McpSchema.Content content : result.content() == null ? List.<McpSchema.Content>of() : result.content()) {
            switch (content) {
                case McpSchema.TextContent part -> append(text, part.text());
                case McpSchema.ImageContent image -> images.add(new LocalImage(image.mimeType(), Base64.getDecoder().decode(image.data())));
                default -> append(text, "[" + content.type() + " content not shown]");
            }
        }
        if (text.length() > MAX_TEXT_CHARS) {
            text.setLength(MAX_TEXT_CHARS);
            text.append("\n[Output truncated on the computer]");
        }
        return new LocalResult(text.toString(), Boolean.TRUE.equals(result.isError()), images);
    }

    private static void append(StringBuilder text, String part) {
        if (part == null || part.isEmpty()) {
            return;
        }
        if (!text.isEmpty()) {
            text.append('\n');
        }
        text.append(part);
    }

    private void stderr(String line) {
        synchronized (stderr) {
            stderr.append(line).append('\n');
            int over = stderr.toString().getBytes(StandardCharsets.UTF_8).length - STDERR_BYTES;
            if (over > 0) {
                stderr.delete(0, Math.min(stderr.length(), over));
            }
        }
    }

    /** stderr 的最后 {@link #REASON_CHARS} 个字符，放进出错原因。 */
    String stderrTail() {
        synchronized (stderr) {
            String all = stderr.toString().strip();
            return all.length() <= REASON_CHARS ? all : all.substring(all.length() - REASON_CHARS);
        }
    }

    /** 先结束子孙进程，再让 SDK 关（关输入、结束直接子进程），最后确认整棵树都没了。 */
    @Override
    public void close() {
        ProcessHandle root = process;
        if (root != null) {
            root.descendants().forEach(ProcessHandle::destroyForcibly);
        }
        if (client != null) {
            try {
                client.closeGracefully();
            } catch (RuntimeException ignored) {
                // 已经断了
            }
        }
        if (root != null) {
            root.destroyForcibly();
            root.onExit().completeOnTimeout(root, 2, TimeUnit.SECONDS).join();
        }
    }
}
