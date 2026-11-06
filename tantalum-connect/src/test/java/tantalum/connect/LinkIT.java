package tantalum.connect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.local.tool.LocalContext;
import tantalum.local.tool.LocalResult;
import tantalum.local.tool.LocalTool;
import tantalum.local.tool.Tools;
import tantalum.shell.boot.Engine;
import tantalum.shell.local.LocalLinks;
import tantalum.shell.local.LocalTokens;
import tantalum.shell.model.ModelOutcome;
import tantalum.shell.web.TestServer;
import tantalum.value.id.Principal;
import tantalum.value.json.Json;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tantalum.value.text.Language;

/**
 * local-workspace 需求 8.1：同一个 JVM 里起真的服务器和真的连接程序，中间隔一个能剪断的 TCP 转发，模拟断网。
 * 服务器这边直接调 {@link LocalLinks#call}，绕过以后的开关和可用性判断，证明电脑这边自己会拒。
 */
class LinkIT {

    /** 已有的断言是中文的：固定用中文跑（console-i18n 任务 12）。 */
    private static final Words ZH = new Words(Language.ZH);

    @TempDir
    static Path temp;

    private static TestServer server;
    private static LocalLinks links;

    private Path work;
    private Proxy proxy;
    private final ByteArrayOutputStream terminal = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(terminal, true, StandardCharsets.UTF_8);
    private final List<Link> started = new ArrayList<>();

    @BeforeAll
    static void startServer() {
        server = new TestServer(temp.resolve("data"), temp.resolve("ws"),
                (prompt, chunks) -> new ModelOutcome.Replied(List.of(new TextBlock("ok")), StopReason.END_TURN, "m", null));
        links = server.context.getBean(Engine.class).localLinks();
    }

    @AfterAll
    static void stopServer() {
        server.close();
    }

    @BeforeEach
    void setUp(@TempDir Path dir) throws Exception {
        work = Files.createDirectory(dir.toRealPath().resolve("work"));
        Files.writeString(work.resolve("a.txt"), "hello world\n");
        proxy = new Proxy(server.port());
    }

    @AfterEach
    void tearDown() throws Exception {
        started.forEach(Link::stop);
        proxy.close();
    }

    private String printed() {
        return terminal.toString(StandardCharsets.UTF_8);
    }

    /** 照真的启动流程算出设置（经 {@link Main#setup}），令牌从环境变量来。 */
    private Main.Setup setup(String token, String... flags) throws Exception {
        List<String> args = new ArrayList<>(List.of("--server", "http://127.0.0.1:" + proxy.port(), "--allow", work.toString()));
        args.addAll(List.of(flags));
        return Main.setup(Main.Options.parse(args.toArray(String[]::new), ZH), Map.of("TANTALUM_TOKEN", token), question -> null, ZH);
    }

    private CompletableFuture<Integer> start(Main.Setup setup, List<LocalTool> tools) {
        Calls calls = new Calls(setup.dirs(), setup.hello().write(), setup.hello().shell().available(),
                new Journal(out, Clock.systemDefaultZone()), tools);
        Link link = new Link(setup.socket(), setup.token(), setup.hello(), calls, out, ZH, List.of(Duration.ofMillis(100)), Link.SILENCE);
        started.add(link);
        CompletableFuture<Integer> exit = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                exit.complete(link.run());
            } catch (Throwable failure) {
                exit.completeExceptionally(failure);
            }
        });
        return exit;
    }

    /** 令牌发给一个真实存在的账号：服务器在握手时查账号，停用或不存在的账号连不上。 */
    private static String token(String user) {
        account(user);
        return links.tokens().issue(new Principal(user), Duration.ofHours(1)).token();
    }

    private static void account(String user) {
        try {
            if (server.users().status(user).isEmpty()) {
                server.users().add(user, TestServer.PASSWORD);
            }
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void awaitConnected(String user) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (links.status(new Principal(user)).isEmpty()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(user + " never connected");
            }
            Thread.sleep(20);
        }
    }

    private static LocalResult call(String user, String tool, Object... pairs) throws InterruptedException {
        Map<String, Json> fields = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            fields.put((String) pairs[i], new Json.Str((String) pairs[i + 1]));
        }
        return links.call(new Principal(user), "s-1", tool, new Json.Obj(fields), Duration.ofSeconds(10));
    }

    /** 生成令牌 → 连上 → 读、改电脑上的文件 → 撤销 → 连接程序说明原因、退出码 2。令牌不出现在终端和服务器的任何文件里。 */
    @Test
    void readEditThenRevoke() throws Exception {
        String token = token("alice");
        CompletableFuture<Integer> exit = start(setup(token, "--allow-write"), Tools.all());
        awaitConnected("alice");
        assertTrue(links.status(new Principal("alice")).orElseThrow().hello().write());

        LocalResult read = call("alice", "read_file", "path", "a.txt");
        assertFalse(read.error(), read.text());
        assertTrue(read.text().contains("hello world"), read.text());
        LocalResult edit = call("alice", "edit_file", "path", "a.txt", "old_string", "hello", "new_string", "hi");
        assertFalse(edit.error(), edit.text());
        assertEquals("hi world\n", Files.readString(work.resolve("a.txt")));

        links.revoke(new Principal("alice"));
        assertEquals(Link.ENDED_BY_SERVER, exit.get(5, TimeUnit.SECONDS));
        assertTrue(printed().contains("已在网页上撤销"), printed());
        assertTrue(printed().contains("read_file a.txt ok"), printed());
        assertTrue(printed().contains("edit_file a.txt ok"), printed());
        assertFalse(printed().contains("hello world"), "no file content in the journal");

        assertFalse(printed().contains(token.substring(3)), "token in the terminal");
        try (Stream<Path> files = Files.walk(temp.resolve("data"))) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String bytes = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
                assertFalse(bytes.contains(token.substring(3)), "token stored in " + file);
            }
        }
    }

    /** 需求 2.3：没有 {@code --allow-write} 时服务器硬发 {@code write_file}，电脑拒绝，文件没写出来。 */
    @Test
    void aForcedWriteIsRefusedByTheComputer() throws Exception {
        start(setup(token("bob")), Tools.all());
        awaitConnected("bob");
        LocalResult write = call("bob", "write_file", "path", "new.txt", "content", "x");
        assertTrue(write.error());
        assertEquals(Calls.NO_WRITE, write.text());
        assertFalse(Files.exists(work.resolve("new.txt")));
        LocalResult escape = call("bob", "read_file", "path", "../../../../etc/hosts");
        assertTrue(escape.error());
        assertTrue(escape.text().startsWith("path not found in the allowed directories"), escape.text());
        assertTrue(printed().contains("write_file new.txt denied"), printed());
    }

    /** 需求 5.4、L8：调用执行到一半连接被剪断：服务器那边得到“结果不确定”，电脑这边打断那个调用。 */
    @Test
    void aCutConnectionEndsTheCallOnBothSides() throws Exception {
        Blocking blocking = new Blocking();
        List<LocalTool> tools = new ArrayList<>(Tools.all());
        tools.add(blocking);
        start(setup(token("carol")), tools);
        awaitConnected("carol");
        CompletableFuture<LocalResult> pending = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                pending.complete(call("carol", "block"));
            } catch (Throwable failure) {
                pending.completeExceptionally(failure);
            }
        });
        assertTrue(blocking.started.await(5, TimeUnit.SECONDS));
        proxy.cut();
        assertEquals(LocalResult.error(LocalLinks.DISCONNECTED), pending.get(5, TimeUnit.SECONDS));
        assertTrue(blocking.interrupted.await(5, TimeUnit.SECONDS), "the computer stops the call");
    }

    /** 需求 1.5：网断了，令牌还有效：连接程序自己重连，之后的调用照常。 */
    @Test
    void afterANetworkDropTheComputerReconnects() throws Exception {
        start(setup(token("dave")), Tools.all());
        awaitConnected("dave");
        assertFalse(call("dave", "read_file", "path", "a.txt").error());

        proxy.cut();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        LocalResult again;
        do {
            Thread.sleep(50);
            again = call("dave", "read_file", "path", "a.txt");
        } while (again.error() && System.nanoTime() < deadline);
        assertFalse(again.error(), again.text());
        assertTrue(again.text().contains("hello world"));
        assertTrue(printed().contains("秒后重连"), printed());
        assertTrue(proxy.accepted() >= 2, "a second TCP connection: " + proxy.accepted());
    }

    /** 需求 1.6：同一个用户的第二个连接程序顶替第一个，第一个说明原因、退出码 2。 */
    @Test
    void aSecondComputerReplacesTheFirst() throws Exception {
        CompletableFuture<Integer> first = start(setup(token("erin")), Tools.all());
        awaitConnected("erin");
        start(setup(token("erin")), Tools.all());
        assertEquals(Link.ENDED_BY_SERVER, first.get(5, TimeUnit.SECONDS));
        assertTrue(printed().contains("已被另一台电脑的连接替换"), printed());
        awaitConnected("erin");
    }

    /** 需求 1.3、1.4：令牌无效时不重连；令牌到期时连着的连接程序说明原因退出。 */
    @Test
    void anInvalidOrExpiringTokenEndsTheConnector() throws Exception {
        CompletableFuture<Integer> invalid = start(setup("tc_" + "B".repeat(43)), Tools.all());
        assertEquals(Link.ENDED_BY_SERVER, invalid.get(5, TimeUnit.SECONDS));
        assertTrue(printed().contains("令牌无效"), printed());

        String soon;
        Duration validity = Duration.ofMinutes(30);
        try (LocalTokens shifted = LocalTokens.open(temp.resolve("data"),
                Clock.offset(Clock.systemUTC(), Duration.ofMillis(1500).minus(validity)))) {
            account("frank");
            soon = shifted.issue(new Principal("frank"), validity).token();
        }
        CompletableFuture<Integer> expiring = start(setup(soon), Tools.all());
        awaitConnected("frank");
        assertEquals(Link.ENDED_BY_SERVER, expiring.get(5, TimeUnit.SECONDS));
        assertTrue(printed().contains("令牌已到期"), printed());
    }

    /** 一直等到被打断的工具。 */
    private static final class Blocking implements LocalTool {

        final java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch interrupted = new java.util.concurrent.CountDownLatch(1);

        @Override public String name() { return "block"; }
        @Override public String description() { return "blocks"; }
        @Override public Json schema() { return new Json.Obj(Map.of()); }
        @Override public Kind kind() { return Kind.READ; }

        @Override
        public LocalResult run(LocalContext context, Json arguments) throws InterruptedException {
            started.countDown();
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException stopped) {
                interrupted.countDown();
                throw stopped;
            }
            return LocalResult.ok("done");
        }
    }

    /** 本机的 TCP 转发：连接程序连它，它连服务器。{@link #cut()} 把现有的连接全部剪断，新连接照常接。 */
    private static final class Proxy implements AutoCloseable {

        private final ServerSocket listener;
        private final int target;
        private final List<Socket> sockets = new CopyOnWriteArrayList<>();
        private final BlockingQueue<Integer> accepted = new LinkedBlockingQueue<>();

        Proxy(int target) throws IOException {
            this.target = target;
            this.listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            Thread.ofVirtual().start(this::accept);
        }

        int port() {
            return listener.getLocalPort();
        }

        int accepted() {
            return accepted.size();
        }

        private void accept() {
            while (!listener.isClosed()) {
                try {
                    Socket client = listener.accept();
                    Socket upstream = new Socket(InetAddress.getLoopbackAddress(), target);
                    sockets.add(client);
                    sockets.add(upstream);
                    accepted.add(1);
                    Thread.ofVirtual().start(() -> pipe(client, upstream));
                    Thread.ofVirtual().start(() -> pipe(upstream, client));
                } catch (IOException closed) {
                    return;
                }
            }
        }

        private static void pipe(Socket from, Socket to) {
            try (InputStream in = from.getInputStream(); OutputStream outStream = to.getOutputStream()) {
                in.transferTo(outStream);
            } catch (IOException cut) {
                // 剪断了
            } finally {
                closeQuietly(from);
                closeQuietly(to);
            }
        }

        void cut() {
            for (Socket socket : sockets) {
                closeQuietly(socket);
            }
            sockets.clear();
        }

        private static void closeQuietly(Socket socket) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 已经关了
            }
        }

        @Override
        public void close() throws IOException {
            listener.close();
            cut();
        }
    }
}
