package tantalum.shell.local;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import tantalum.local.fs.AllowedDirs;
import tantalum.local.tool.LocalContext;
import tantalum.local.tool.LocalResult;
import tantalum.local.tool.LocalTool;
import tantalum.local.tool.Observations;
import tantalum.local.tool.Tools;
import tantalum.local.wire.Call;
import tantalum.local.wire.Cancel;
import tantalum.local.wire.Context;
import tantalum.local.wire.ContextResult;
import tantalum.local.wire.Hello;
import tantalum.local.wire.Message;
import tantalum.local.wire.Result;
import tantalum.local.wire.Wire;
import tantalum.value.id.Principal;

/**
 * 测试用的电脑：不经过 WebSocket，直接接到 {@link LocalLinks} 上，用 {@code tantalum-local} 的真实工具在允许的目录里执行调用。
 * 和真的连接程序一样自己再查一遍改文件的权限（L4）。{@link #holdCalls()} 之后收到的调用停住不回，用来测“调用途中断开”。
 * 真的连接程序和 WebSocket 见 {@code tantalum-connect} 的 {@code LinkIT}。
 */
public final class TestComputer implements LocalLinks.Peer, AutoCloseable {

    private final Hello hello;
    private final AllowedDirs dirs;
    private final Map<String, Observations> observations = new ConcurrentHashMap<>();
    private final Map<String, Thread> running = new ConcurrentHashMap<>();
    private final BlockingQueue<Call> calls = new LinkedBlockingQueue<>();
    private volatile CountDownLatch hold;
    private volatile boolean silentContext;
    private volatile LocalLinks.Link link;
    private volatile boolean closed;

    private TestComputer(Hello hello, AllowedDirs dirs) {
        this.hello = hello;
        this.dirs = dirs;
    }

    /** 把 {@code main}（第一个允许的目录）连给 {@code user}，到 {@code expiresAt} 为止。{@code git} 只是告诉服务器这台电脑装没装 git。 */
    public static TestComputer connect(LocalLinks links, Principal user, Instant expiresAt, Path main, boolean write, boolean git)
            throws IOException {
        Path real = main.toRealPath();
        Hello hello = new Hello(Wire.PROTOCOL, "test", new Hello.Device("Test Mac", "macos", "26.2", "arm64"),
                List.of(new Hello.Root(real.toString())), write, new Hello.Shell(false, "--allow-shell was not given"), true, git);
        TestComputer computer = new TestComputer(hello, new AllowedDirs(List.of(real)));
        computer.link = links.attach(user, expiresAt, System.nanoTime(), hello, computer)
                .orElseThrow(() -> new IllegalStateException("revoked"));
        return computer;
    }

    public Hello hello() {
        return hello;
    }

    /** 之后收到的调用都停住，直到断开（调用线程被打断）。之前收到的调用不再算进 {@link #nextCall}。 */
    public void holdCalls() {
        hold = new CountDownLatch(1);
        calls.clear();
    }

    /** 之后不回 {@code AGENTS.md} 的请求，像电脑卡住了一样。 */
    public void ignoreContextRequests() {
        silentContext = true;
    }

    /** 等下一个调用到达；超时返回 null。 */
    public Call nextCall(long seconds) throws InterruptedException {
        return calls.poll(seconds, TimeUnit.SECONDS);
    }

    /** 像网线断了一样：服务器那边按断开处理，这边结束在跑的调用。 */
    public void disconnect() {
        closed = true;
        running.values().forEach(Thread::interrupt);
        LocalLinks.Link current = link;
        if (current != null) {
            current.dropped();
        }
    }

    public boolean closed() {
        return closed;
    }

    @Override
    public void close() {
        disconnect();
    }

    @Override
    public void send(String text) throws IOException {
        if (closed) {
            throw new IOException("closed");
        }
        Message message = Wire.decode(text);
        switch (message) {
            case Call call -> {
                calls.add(call);
                running.put(call.id(), Thread.ofVirtual().name("test-computer-" + call.id()).start(() -> execute(call)));
            }
            case Cancel cancel -> {
                Thread thread = running.get(cancel.id());
                if (thread != null) {
                    thread.interrupt();
                }
            }
            case Context context -> {
                if (!silentContext) {
                    Thread.ofVirtual().start(() -> reply(new ContextResult(context.id(), agents().orElse(null))));
                }
            }
            default -> { } // welcome、bye
        }
    }

    @Override
    public void ping() {
        LocalLinks.Link current = link;
        if (current != null) {
            current.pong();
        }
    }

    @Override
    public void close(int code, String reason) {
        disconnect();
    }

    private void execute(Call call) {
        try {
            CountDownLatch waiting = hold;
            if (waiting != null) {
                waiting.await();
            }
            reply(Result.of(call.id(), run(call)));
        } catch (InterruptedException cancelled) {
            // 取消或断开：不回结果
        } finally {
            running.remove(call.id());
        }
    }

    private LocalResult run(Call call) {
        Optional<LocalTool> tool = Tools.all().stream().filter(candidate -> candidate.name().equals(call.tool())).findFirst();
        if (tool.isEmpty()) {
            return LocalResult.error("Unknown tool: " + call.tool());
        }
        if (tool.get().kind() == LocalTool.Kind.WRITE && !hello.write()) {
            return LocalResult.error("This computer does not allow file changes.");
        }
        try {
            Observations seen = observations.computeIfAbsent(call.session(), ignored -> new Observations());
            return tool.get().run(new LocalContext(call.session(), dirs, seen), call.arguments());
        } catch (Exception failure) {
            return LocalResult.error("Tool failed: " + failure);
        }
    }

    /** 和连接程序一样经 {@link AllowedDirs} 找主目录里的 {@code AGENTS.md}；这里不截断，服务器那边自己再截。 */
    private Optional<String> agents() {
        try {
            Path file = dirs.resolve("AGENTS.md");
            return Files.isRegularFile(file) ? Optional.of(Files.readString(file, StandardCharsets.UTF_8)) : Optional.empty();
        } catch (IOException | IllegalArgumentException missing) {
            return Optional.empty();
        }
    }

    private void reply(Message message) {
        LocalLinks.Link current = link;
        if (current != null && !closed) {
            current.receive(message);
        }
    }
}
