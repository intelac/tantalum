package tantalum.shell.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.McpHttpClientTransportAuthorizationException;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.shell.plugin.ToolOutput;

/**
 * 到一个服务器的连接。后台虚拟线程负责建立、探活和重连，每次重连都换新客户端（旧客户端在服务器重启后永久失效）。
 * 断线期间工具保持可见，调用返回错误结果；连续失败到上限才移除工具，避免工具列表抖动。
 * 日志只记状态和错误类别，不记消息原文，防止回显凭据或内部地址。
 */
final class McpConnection implements AutoCloseable {

    enum State { CONNECTING, READY, RECONNECTING, UNAUTHORIZED, FAILED, CLOSED }

    /** {@code stableWindow}：连接存活超过它才重置失败计数，防止“连上就断”绕过上限。 */
    record Policy(Duration initialDelay, Duration maxDelay, int maxAttempts, Duration stableWindow, Duration failedRetry, Duration pingInterval) {
        static Policy defaults() {
            return new Policy(Duration.ofMillis(500), Duration.ofSeconds(30), 10, Duration.ofSeconds(30), Duration.ofMinutes(5), Duration.ofSeconds(30));
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(McpConnection.class);
    private static final Duration RETRY_WAIT = Duration.ofSeconds(2);

    private final McpSettings.Server server;
    private final Policy policy;
    private final Consumer<List<McpSchema.Tool>> onTools;
    private final Object lock = new Object();
    private final Thread supervisor;
    private volatile State state = State.CONNECTING;
    private volatile List<McpSchema.Tool> tools = List.of();
    private volatile McpSyncClient client;
    private boolean broken;

    McpConnection(McpSettings.Server server, Policy policy, Consumer<List<McpSchema.Tool>> onTools) {
        this.server = server;
        this.policy = policy;
        this.onTools = onTools;
        this.supervisor = Thread.ofVirtual().name("mcp-" + server.name()).start(this::supervise);
    }

    State state() {
        return state;
    }

    List<McpSchema.Tool> tools() {
        return tools;
    }

    private void supervise() {
        int failures = 0;
        Duration delay = policy.initialDelay();
        try {
            while (state != State.CLOSED) {
                long connectedAt = 0;
                try {
                    connectOnce();
                    connectedAt = System.nanoTime();
                    move(State.READY);
                    watch();
                } catch (InterruptedException stopped) {
                    throw stopped;
                } catch (RuntimeException failure) {
                    if (isInterrupt(failure)) {
                        throw new InterruptedException();
                    }
                    LOG.warn("mcp server {}: {}", server.name(), category(failure));
                    if (isUnauthorized(failure)) {
                        giveUp(State.UNAUTHORIZED);
                        discardClient();
                        Thread.sleep(policy.failedRetry());
                        continue;
                    }
                }
                discardClient();
                if (state == State.CLOSED) {
                    return;
                }
                boolean stable = connectedAt != 0 && Duration.ofNanos(System.nanoTime() - connectedAt).compareTo(policy.stableWindow()) >= 0;
                if (stable) {
                    failures = 0;
                    delay = policy.initialDelay();
                }
                failures++;
                if (failures >= policy.maxAttempts()) {
                    giveUp(State.FAILED);
                    Thread.sleep(policy.failedRetry());
                    continue;
                }
                move(State.RECONNECTING);
                Thread.sleep(delay);
                delay = delay.multipliedBy(2).compareTo(policy.maxDelay()) > 0 ? policy.maxDelay() : delay.multipliedBy(2);
            }
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        } finally {
            discardClient();
        }
    }

    private void connectOnce() {
        McpSyncClient fresh = McpTools.client(server, this::toolsChanged);
        client = fresh;
        fresh.initialize();
        publish(McpTools.listAll(fresh));
        synchronized (lock) {
            broken = false;
        }
    }

    /** 等到连接被判定为坏：调用发现它坏了，或定期探活失败。 */
    private void watch() throws InterruptedException {
        while (true) {
            synchronized (lock) {
                if (broken) {
                    return;
                }
                lock.wait(policy.pingInterval().toMillis());
                if (broken) {
                    return;
                }
            }
            client.ping();
        }
    }

    private void toolsChanged(List<McpSchema.Tool> changed) {
        publish(List.copyOf(changed));
    }

    private void publish(List<McpSchema.Tool> changed) {
        tools = changed;
        onTools.accept(changed);
    }

    private void giveUp(State terminal) {
        move(terminal);
        if (!tools.isEmpty()) {
            LOG.error("mcp server {}: removing its tools after repeated failures", server.name());
            publish(List.of());
        }
    }

    private void move(State next) {
        if (state != next && state != State.CLOSED) {
            LOG.info("mcp server {}: {} -> {}", server.name(), state, next);
            state = next;
        }
    }

    private void discardClient() {
        McpSyncClient old = client;
        client = null;
        if (old != null) {
            try {
                old.close();
            } catch (RuntimeException ignored) {
                // 连接已经坏了，关闭失败没有后果。
            }
        }
    }

    private void markBroken(McpSyncClient failed) {
        synchronized (lock) {
            if (client == failed) {
                broken = true;
                lock.notifyAll();
            }
        }
    }

    /**
     * 调用工具。失败都变成错误结果交给模型，只有中断原样抛出。
     * {@code retrySafe} 的工具（声明只读）在连接失效时换新连接后重试一次；其余工具结果可能未知，不重试。
     */
    ToolOutput call(String tool, Map<String, Object> arguments, boolean retrySafe) throws InterruptedException {
        ToolOutput first = attempt(tool, arguments, !retrySafe);
        if (first != null) {
            return first;
        }
        awaitReady();
        ToolOutput second = attempt(tool, arguments, true);
        return second != null ? second : unavailable();
    }

    /** 返回 null 表示连接失效且可以重试。 */
    private ToolOutput attempt(String tool, Map<String, Object> arguments, boolean last) throws InterruptedException {
        McpSyncClient current = client;
        if (current == null || state != State.READY) {
            return last ? unavailable() : null;
        }
        try {
            McpSchema.CallToolResult result = current.callTool(new McpSchema.CallToolRequest(tool, arguments));
            return McpResults.toOutput(result, server.maxResultBytes());
        } catch (RuntimeException failure) {
            if (isInterrupt(failure)) {
                throw new InterruptedException();
            }
            if (failure instanceof McpError protocol) {
                return ToolOutput.error("MCP server " + server.name() + " rejected the call: " + protocol.getMessage());
            }
            if (has(failure, TimeoutException.class)) {
                return ToolOutput.error("MCP tool timed out after " + server.timeout() + ". Its outcome is unknown.");
            }
            markBroken(current);
            if (isUnauthorized(failure)) {
                return ToolOutput.error("MCP server " + server.name() + " refused the configured credentials.");
            }
            LOG.warn("mcp server {}: call failed: {}", server.name(), category(failure));
            return last ? ToolOutput.error("MCP server " + server.name() + " is unavailable. The outcome of this call is unknown.") : null;
        }
    }

    private void awaitReady() throws InterruptedException {
        long deadline = System.nanoTime() + RETRY_WAIT.toNanos();
        Thread.sleep(50);
        while (state != State.READY && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
    }

    private ToolOutput unavailable() {
        return ToolOutput.error("MCP server " + server.name() + " is unavailable.");
    }

    private static boolean isInterrupt(Throwable failure) {
        return Thread.currentThread().isInterrupted() || has(failure, InterruptedException.class);
    }

    private static boolean isUnauthorized(Throwable failure) {
        return has(failure, McpHttpClientTransportAuthorizationException.class);
    }

    private static boolean has(Throwable failure, Class<? extends Throwable> type) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
            if (cause.getCause() == cause) {
                return false;
            }
        }
        return false;
    }

    /** 只给类别，不给消息原文。 */
    private static String category(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName();
    }

    @Override
    public void close() {
        state = State.CLOSED;
        supervisor.interrupt();
    }
}
