package tantalum.connect.browser;

import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tantalum.local.tool.LocalResult;
import tantalum.value.json.Json;

/**
 * 看着 Playwright MCP 这一个进程（connector-jar 需求 7；remove-generic-mcp 前是本机 MCP 的通用宿主 {@code McpHost}，现在只管浏览器）。
 * <ul>
 *   <li>{@link #start()} 在后台起进程，不等它好；调用时还没好、正在重启或已放弃，回错误结果。</li>
 *   <li>退避重连照 DSH（{@code packages/mcp/mcp-client/src/connection.ts:27-46,196-224}）：500 毫秒起翻倍、30 秒封顶，一次故障最多
 *       10 次，稳定 30 秒后清零；用完就停，原因是 stderr 的末尾。状态变化写进连接程序自己的输出（stderr），不报给服务器。</li>
 * </ul>
 */
public final class PlaywrightHost implements AutoCloseable {

    static final Duration FIRST_DELAY = Duration.ofMillis(500);
    static final Duration MAX_DELAY = Duration.ofSeconds(30);
    static final int MAX_ATTEMPTS = 10;
    static final Duration STABLE = Duration.ofSeconds(30);
    static final Duration CALL_TIMEOUT = Duration.ofSeconds(60);
    /** 多久看一次子进程还在不在。 */
    static final Duration WATCH = Duration.ofMillis(500);

    /** 现在怎样。 */
    public enum State { STARTING, READY, FAILED }

    private final ServerCommand command;
    private final Map<String, String> env;
    private final Path cwd;
    private final Duration firstDelay;
    private final Duration maxDelay;
    private final int maxAttempts;
    private volatile Thread thread;
    private volatile PlaywrightProcess process;
    private volatile State state = State.STARTING;
    private volatile String reason;
    private volatile boolean closed;

    /** {@code env} 是给子进程的全部环境变量（{@link tantalum.local.shell.CommandEnv}），{@code cwd} 是它的工作目录。 */
    public PlaywrightHost(ServerCommand command, Map<String, String> env, Path cwd) {
        this(command, env, cwd, FIRST_DELAY, MAX_DELAY, MAX_ATTEMPTS);
    }

    /** 测试用：退避的时间和次数。 */
    PlaywrightHost(ServerCommand command, Map<String, String> env, Path cwd, Duration firstDelay, Duration maxDelay, int maxAttempts) {
        this.command = command;
        this.env = Map.copyOf(env);
        this.cwd = cwd;
        this.firstDelay = firstDelay;
        this.maxDelay = maxDelay;
        this.maxAttempts = maxAttempts;
    }

    public State state() {
        return state;
    }

    /** 出错、停下的原因（英文，可能带 stderr 的末尾）；好的时候为 null。 */
    public String reason() {
        return reason;
    }

    /** 后台启动；不等它好。 */
    public void start() {
        thread = Thread.ofVirtual().name("playwright-mcp").start(this::supervise);
    }

    /** 调 Playwright MCP 的一个工具。没好、调用出错都回错误结果，不抛异常（线程被打断除外）。 */
    public LocalResult call(String tool, Json arguments) throws InterruptedException {
        PlaywrightProcess current = process;
        if (current == null || state != State.READY) {
            return LocalResult.error("The browser is not ready (" + state.name().toLowerCase(Locale.ROOT)
                    + (reason == null ? "" : ": " + reason) + ").");
        }
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        try {
            return current.call(tool, arguments);
        } catch (RuntimeException failure) {
            return LocalResult.error("Playwright MCP failed: " + message(failure));
        }
    }

    /** 启动，看着它；断了按退避重连，次数用完停下。 */
    private void supervise() {
        int attempts = 0;
        Duration delay = firstDelay;
        while (!closed && !Thread.currentThread().isInterrupted()) {
            long started = System.nanoTime();
            PlaywrightProcess fresh = new PlaywrightProcess(command);
            try {
                fresh.start(env, cwd, CALL_TIMEOUT);
                process = fresh;
                state = State.READY;
                reason = null;
                while (!closed && fresh.alive()) {
                    Thread.sleep(WATCH);
                }
                reason = "Playwright MCP exited" + tail(fresh);
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
                System.err.println("[tantalum-connect] the browser (Playwright MCP) stopped after " + attempts + " attempts: " + reason);
                return;
            }
            state = State.STARTING;
            try {
                Thread.sleep(delay);
            } catch (InterruptedException stopped) {
                return;
            }
            delay = delay.multipliedBy(2).compareTo(maxDelay) > 0 ? maxDelay : delay.multipliedBy(2);
        }
    }

    @Override
    public void close() {
        closed = true;
        Thread current = thread;
        if (current != null) {
            current.interrupt();
        }
        PlaywrightProcess running = process;
        if (running != null) {
            running.close();
        }
    }

    private static String tail(PlaywrightProcess process) {
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

    /** 测试用：Playwright MCP 现在报的工具。 */
    List<McpSchema.Tool> tools() {
        PlaywrightProcess current = process;
        return current == null ? List.of() : current.tools();
    }
}
