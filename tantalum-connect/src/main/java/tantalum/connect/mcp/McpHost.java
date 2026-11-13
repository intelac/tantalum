package tantalum.connect.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import tantalum.local.tool.LocalResult;
import tantalum.local.wire.McpServerState;
import tantalum.local.wire.McpServerState.State;
import tantalum.value.json.Json;

/**
 * 本机 MCP 的宿主（connector-jar 需求 6）：按 {@link McpConfig} 启动每个服务器，各自一个虚拟线程监督。
 * <ul>
 *   <li>启动、{@code list_changed}、崩溃、重连、用完次数都把这个服务器的最新状态交给 {@code report}（连接程序发成 {@code mcpTools}）。</li>
 *   <li>退避重连照 DSH（{@code packages/mcp/mcp-client/src/connection.ts:27-46,196-224}）：500 毫秒起翻倍、30 秒封顶，一次故障最多
 *       10 次，稳定 30 秒后清零；用完停在 {@code failed}，工具撤掉，原因是 stderr 的末尾。</li>
 *   <li>重连期间工具保留（清单不抖动，服务器上的缓存前缀不变），调用返回错误。</li>
 * </ul>
 * 调用的名字是 {@code mcp:服务器:工具}（{@link #CALL_PREFIX}），由服务器上的 {@code LocalMcpTool} 拼出来；名字里的服务器部分只能是
 * {@link McpConfig#NAME}，不含冒号。
 */
public final class McpHost implements AutoCloseable {

    /** 本机 MCP 调用在 {@code call.tool} 里的前缀。 */
    public static final String CALL_PREFIX = "mcp:";
    static final Duration FIRST_DELAY = Duration.ofMillis(500);
    static final Duration MAX_DELAY = Duration.ofSeconds(30);
    static final int MAX_ATTEMPTS = 10;
    static final Duration STABLE = Duration.ofSeconds(30);
    static final Duration CALL_TIMEOUT = Duration.ofSeconds(60);
    /** 多久看一次子进程还在不在。 */
    static final Duration WATCH = Duration.ofMillis(500);

    private final McpConfig config;
    private final Map<String, String> env;
    private final Path mainDir;
    private final Consumer<McpServerState> report;
    private final Duration firstDelay;
    private final Duration maxDelay;
    private final int maxAttempts;
    private final Map<String, Server> servers = new LinkedHashMap<>();
    private volatile boolean closed;

    public McpHost(McpConfig config, Map<String, String> env, Path mainDir, Consumer<McpServerState> report) {
        this(config, env, mainDir, report, FIRST_DELAY, MAX_DELAY, MAX_ATTEMPTS);
    }

    /** 测试用：退避的时间和次数。 */
    McpHost(McpConfig config, Map<String, String> env, Path mainDir, Consumer<McpServerState> report, Duration firstDelay,
            Duration maxDelay, int maxAttempts) {
        this.firstDelay = firstDelay;
        this.maxDelay = maxDelay;
        this.maxAttempts = maxAttempts;
        this.config = config;
        this.env = Map.copyOf(env);
        this.mainDir = mainDir;
        this.report = report;
        for (McpConfig.Entry entry : config.servers()) {
            servers.put(entry.name(), new Server(entry));
        }
    }

    /** 连上服务器之前的状态（{@code hello.mcp}）：停用的写明，其余都是启动中。 */
    public List<McpServerState> states() {
        return servers.values().stream().map(Server::state).toList();
    }

    public McpConfig config() {
        return config;
    }

    /** 后台启动全部没停用的服务器；不等它们好。 */
    public void start() {
        for (Server server : servers.values()) {
            if (!server.entry.disabled()) {
                server.thread = Thread.ofVirtual().name("mcp-" + server.entry.name()).start(server::supervise);
            }
        }
    }

    /** 这次调用是不是给本机 MCP 的。 */
    public static boolean owns(String tool) {
        return tool.startsWith(CALL_PREFIX);
    }

    /** 调 {@code mcp:服务器:工具}。服务器不在、没好、调用出错都回错误结果，不抛异常（线程被打断除外）。 */
    public LocalResult call(String tool, Json arguments) throws InterruptedException {
        String rest = tool.substring(CALL_PREFIX.length());
        int colon = rest.indexOf(':');
        if (colon < 0) {
            return LocalResult.error("Unknown tool: " + tool);
        }
        Server server = servers.get(rest.substring(0, colon));
        if (server == null) {
            return LocalResult.error("No MCP server named " + rest.substring(0, colon) + " on this computer.");
        }
        return server.call(rest.substring(colon + 1), arguments);
    }

    @Override
    public void close() {
        closed = true;
        for (Server server : servers.values()) {
            if (server.thread != null) {
                server.thread.interrupt();
            }
            server.stop();
        }
    }

    /** 一个服务器：当前的进程、状态和监督线程。 */
    private final class Server {

        final McpConfig.Entry entry;
        volatile Thread thread;
        volatile McpProcess process;
        volatile State state;
        volatile String reason;
        volatile List<McpServerState.McpToolSpec> tools = List.of();

        Server(McpConfig.Entry entry) {
            this.entry = entry;
            this.state = entry.disabled() ? State.DISABLED : State.STARTING;
            this.reason = entry.disabled() ? "disabled in mcp.json" : null;
        }

        McpServerState state() {
            return new McpServerState(entry.name(), state, reason, tools);
        }

        private void publish() {
            report.accept(state());
        }

        /** 启动，看着它；断了按退避重连，次数用完停下。 */
        void supervise() {
            int attempts = 0;
            Duration delay = firstDelay;
            while (!closed && !Thread.currentThread().isInterrupted()) {
                long started = System.nanoTime();
                McpProcess fresh = new McpProcess(entry);
                try {
                    fresh.start(env, mainDir, CALL_TIMEOUT, changed -> {
                        tools = McpProcess.specs(changed);
                        publish();
                    });
                    process = fresh;
                    tools = McpProcess.specs(fresh.tools());
                    state = State.READY;
                    reason = null;
                    publish();
                    while (!closed && fresh.alive()) {
                        Thread.sleep(WATCH);
                    }
                    reason = "the MCP server exited" + tail(fresh);
                } catch (InterruptedException stopped) {
                    fresh.close();
                    return;
                } catch (RuntimeException failure) {
                    reason = "cannot start: " + message(failure) + tail(fresh);
                }
                fresh.close();
                process = null;
                if (closed) {
                    return;
                }
                if (System.nanoTime() - started >= STABLE.toNanos()) {
                    attempts = 0;
                    delay = firstDelay;
                }
                attempts++;
                if (attempts >= maxAttempts) {
                    state = State.FAILED;
                    tools = List.of();
                    publish();
                    System.err.println("[tantalum-connect] MCP server " + entry.name() + " stopped after " + attempts + " attempts: " + reason);
                    return;
                }
                // 重连期间工具保留，状态写“启动中”
                state = State.STARTING;
                publish();
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException stopped) {
                    return;
                }
                delay = delay.multipliedBy(2).compareTo(maxDelay) > 0 ? maxDelay : delay.multipliedBy(2);
            }
        }

        LocalResult call(String tool, Json arguments) throws InterruptedException {
            McpProcess current = process;
            if (current == null || state != State.READY) {
                return LocalResult.error("The MCP server " + entry.name() + " is not ready (" + state.name().toLowerCase(java.util.Locale.ROOT)
                        + (reason == null ? "" : ": " + reason) + ").");
            }
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            try {
                return current.call(tool, arguments);
            } catch (RuntimeException failure) {
                return LocalResult.error("The MCP server " + entry.name() + " failed: " + message(failure));
            }
        }

        void stop() {
            McpProcess current = process;
            if (current != null) {
                current.close();
            }
        }
    }

    private static String tail(McpProcess process) {
        String tail = process.stderrTail();
        return tail.isEmpty() ? "" : "; stderr: " + tail;
    }

    private static String message(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getMessage() != null && cause.getMessage().startsWith("java.")) {
            cause = cause.getCause();
        }
        String text = cause.getMessage();
        return text == null ? cause.getClass().getSimpleName() : text;
    }

    /** 测试用：现在的工具（每个服务器最新报的）。 */
    List<McpSchema.Tool> toolsOf(String server) {
        McpProcess current = servers.get(server).process;
        return current == null ? List.of() : current.tools();
    }
}
