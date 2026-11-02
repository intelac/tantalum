package tantalum.shell.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.McpHttpClientTransportAuthorizationException;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpTransportSessionNotFoundException;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
    private static final Duration RETRY_WAIT = Duration.ofSeconds(5);

    private final McpSettings.Server server;
    private final Policy policy;
    private final Consumer<List<McpSchema.Tool>> onTools;
    private final Object lock = new Object();
    private volatile Thread supervisor;
    private volatile State state = State.CONNECTING;
    private volatile List<McpSchema.Tool> tools = List.of();
    private volatile McpSyncClient client;
    private boolean broken;
    /** 会话失效引起的换客户端：不算失败，不退避。 */
    private boolean rotating;
    private volatile long generation;
    private final Set<Call> inFlight = new HashSet<>();
    /** 换下来的客户端：等在途调用走完再关。 */
    private final Set<McpSyncClient> retired = ConcurrentHashMap.newKeySet();
    private final CountDownLatch firstAttempt = new CountDownLatch(1);

    McpConnection(McpSettings.Server server, Policy policy, Consumer<List<McpSchema.Tool>> onTools) {
        this.server = server;
        this.policy = policy;
        this.onTools = onTools;
    }

    /** 和构造分开：回调可能立刻发生，调用方要先把自己准备好。 */
    McpConnection start() {
        supervisor = Thread.ofVirtual().name("mcp-" + server.name()).start(this::supervise);
        return this;
    }

    State state() {
        return state;
    }

    /** 等第一次连接尝试结束（无论成败），最多等 {@code limit}。 */
    void awaitFirstAttempt(Duration limit) throws InterruptedException {
        firstAttempt.await(limit.toMillis(), TimeUnit.MILLISECONDS);
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
                    firstAttempt.countDown();
                    watch();
                } catch (InterruptedException stopped) {
                    throw stopped;
                } catch (RuntimeException failure) {
                    if (isInterrupt(failure)) {
                        throw new InterruptedException();
                    }
                    LOG.warn("mcp server {}: {}", server.name(), category(failure));
                    firstAttempt.countDown();
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
                if (takeRotating()) {
                    continue;
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
        } catch (Throwable fatal) {
            // 比如 SDK 依赖缺失。不能让监督线程静默消失，工具会永远不可用却没人知道。
            LOG.error("mcp server {}: connection supervisor died", server.name(), fatal);
            move(State.FAILED);
        } finally {
            firstAttempt.countDown();
            discardClient();
            retired.forEach(McpConnection::closeQuietly);
        }
    }

    private void connectOnce() {
        long current = ++generation;
        McpSyncClient fresh = McpClients.client(server, changed -> toolsChanged(current, changed));
        client = fresh;
        fresh.initialize();
        boolean hasTools = fresh.getServerCapabilities() != null && fresh.getServerCapabilities().tools() != null;
        publish(hasTools ? McpClients.listAll(fresh) : List.of());
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
            McpSyncClient current = client;
            if (current == null) {
                return;
            }
            current.ping();
        }
    }

    /** 旧客户端迟到的回调不算数，否则会覆盖新列表，或在移除后把工具加回来。 */
    void toolsChanged(long from, List<McpSchema.Tool> changed) {
        if (from == generation) {
            publish(List.copyOf(changed));
        }
    }

    long generation() {
        return generation;
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
            closeQuietly(old);
        }
    }

    private static void closeQuietly(McpSyncClient old) {
        try {
            old.close();
        } catch (RuntimeException ignored) {
            // 连接已经坏了，关闭失败没有后果。
        }
    }

    /** 会话失效：摘下这个客户端让监督线程换新的。它不立刻关，其他在途调用要走完（pi 的做法）。 */
    private void rotate(McpSyncClient expired) {
        synchronized (lock) {
            if (client != expired) {
                return;
            }
            client = null;
            rotating = true;
            broken = true;
            move(State.RECONNECTING);
            retired.add(expired);
            lock.notifyAll();
        }
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(server.timeout());
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            }
            if (retired.remove(expired)) {
                closeQuietly(expired);
            }
        });
    }

    private boolean takeRotating() {
        synchronized (lock) {
            boolean was = rotating;
            rotating = false;
            return was;
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
     * 只有确定没有执行的会话失效才重试一次，所以写工具也安全。其他失败不碰共享连接，下线由探活发现。
     */
    ToolOutput call(String tool, Map<String, Object> arguments) throws InterruptedException {
        ToolOutput first = attempt(tool, arguments, false);
        if (first != null) {
            return first;
        }
        waitForReconnect();
        ToolOutput second = attempt(tool, arguments, true);
        return second != null ? second : unavailable();
    }

    /** 一次进行中的调用。{@code overlapped} 表示期间连接上还有别的调用。 */
    private static final class Call {
        volatile boolean overlapped;
    }

    /** 返回 null 表示这次没有执行，可以重试。 */
    private ToolOutput attempt(String tool, Map<String, Object> arguments, boolean last) throws InterruptedException {
        McpSyncClient current = client;
        if (current == null || state != State.READY) {
            return last ? unavailable() : null;
        }
        Call call = new Call();
        synchronized (inFlight) {
            if (!inFlight.isEmpty()) {
                call.overlapped = true;
                inFlight.forEach(other -> other.overlapped = true);
            }
            inFlight.add(call);
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
            if (isSessionLost(failure)) {
                rotate(current);
                // 会话失效时 SDK 用同一个错误结束所有在途调用，分不清哪个被服务器拒绝、哪个已经执行。
                // 只有期间没有别的调用才能确定是这次被拒绝，重试才不会重复执行。
                if (!call.overlapped) {
                    return last ? unavailable() : null;
                }
                return ToolOutput.error("MCP server " + server.name() + " lost the session during this call. Its outcome is unknown.");
            }
            if (isUnauthorized(failure)) {
                markBroken(current);
                return ToolOutput.error("MCP server " + server.name() + " refused the configured credentials.");
            }
            LOG.warn("mcp server {}: call failed: {}", server.name(), category(failure));
            return ToolOutput.error("MCP server " + server.name() + " failed this call. Its outcome is unknown.");
        } finally {
            synchronized (inFlight) {
                inFlight.remove(call);
            }
        }
    }

    /** SDK 2.1.0 把 404 变成这条没有原因的 RuntimeException（{@code McpClientSession.dismissPendingResponses}），只能靠消息识别。 */
    private static boolean isSessionLost(Throwable failure) {
        return has(failure, McpTransportSessionNotFoundException.class)
                || failure.getMessage() != null && failure.getMessage().contains("session with server terminated");
    }

    private void waitForReconnect() throws InterruptedException {
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
        retired.forEach(McpConnection::closeQuietly);
        retired.clear();
        if (supervisor != null) {
            supervisor.interrupt();
        }
    }
}
