package tantalum.shell.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.local.tool.LocalResult;
import tantalum.local.wire.Bye;
import tantalum.local.wire.Call;
import tantalum.local.wire.Hello;
import tantalum.local.wire.Message;
import tantalum.local.wire.Result;
import tantalum.local.wire.Welcome;
import tantalum.local.wire.Wire;
import tantalum.shell.boot.Engine;
import tantalum.shell.model.ModelOutcome;
import tantalum.shell.web.TestServer;
import tantalum.value.id.Principal;
import tantalum.value.json.Json;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;

/**
 * local-workspace 需求 1.2–1.6：真服务器、真 WebSocket（JDK 客户端）。握手的关闭码、替换、到期时刻断开、断开时在跑的调用。
 * 一个服务器给全部用例用，每个用例用自己的用户，互不影响。
 */
class LocalEndpointTest {

    private static final Hello HELLO = new Hello(Wire.PROTOCOL, "test", new Hello.Device("mac", "macos", "26.2", "arm64"),
            List.of(new Hello.Root("/Users/someone/work", true)), false, new Hello.Shell(false, "--allow-shell was not given"), true);
    private static final Json ARGS = new Json.Obj(Map.of("path", new Json.Str("a.txt")));

    @TempDir
    static Path temp;

    private static TestServer server;
    private static LocalLinks links;

    @BeforeAll
    static void start() {
        server = new TestServer(temp.resolve("data"), temp.resolve("ws"),
                (prompt, chunks) -> new ModelOutcome.Replied(List.of(new TextBlock("ok")), StopReason.END_TURN, "m", null));
        links = server.context.getBean(Engine.class).localLinks();
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    /** 令牌发给一个真实存在的账号：停用或不存在的账号连不上（见 {@link #aDisabledAccountIsRefused}）。 */
    private static String token(String user) throws Exception {
        account(user);
        return links.tokens().issue(new Principal(user), Duration.ofHours(1)).token();
    }

    private static void account(String user) throws Exception {
        if (server.users().status(user).isEmpty()) {
            server.users().add(user, TestServer.PASSWORD);
        }
    }

    /** 用另一个时钟在同一个库里发令牌，造出“已经到期”或“马上到期”的令牌。 */
    private static String tokenExpiringIn(String user, Duration left) throws Exception {
        account(user);
        Duration validity = Duration.ofMinutes(30);
        try (LocalTokens shifted = LocalTokens.open(temp.resolve("data"), Clock.offset(Clock.systemUTC(), left.minus(validity)))) {
            return shifted.issue(new Principal(user), validity).token();
        }
    }

    /** 需求 1.3：令牌无效、没带、格式不对都是 4401。 */
    @Test
    void anInvalidTokenIsClosedWith4401() throws Exception {
        for (String bad : new String[] {null, "tc_not-a-real-token", "Basic abc"}) {
            Client client = Client.connect(server.port(), bad);
            assertEquals(Wire.CLOSE_BAD_TOKEN, client.closeCode(), String.valueOf(bad));
        }
        Client wrongScheme = Client.connectRaw(server.port(), "Token " + token("ivan"));
        assertEquals(Wire.CLOSE_BAD_TOKEN, wrongScheme.closeCode());
    }

    /** 需求 1.3：到期、撤销都是 4403，原因写明是哪一种。 */
    @Test
    void anExpiredOrRevokedTokenIsClosedWith4403() throws Exception {
        Client expired = Client.connect(server.port(), tokenExpiringIn("erin", Duration.ofMinutes(-1)));
        assertEquals(Wire.CLOSE_EXPIRED, expired.closeCode());
        assertEquals("expired", expired.closeReason);

        String token = token("rita");
        links.revoke(new Principal("rita"));
        Client revoked = Client.connect(server.port(), token);
        assertEquals(Wire.CLOSE_EXPIRED, revoked.closeCode());
        assertEquals("revoked", revoked.closeReason);
    }

    /** 账号在服务外被停用（命令行 {@code users disable}）或根本不存在：令牌有效也按“已撤销”拒绝，令牌作废。 */
    @Test
    void aDisabledAccountIsRefused() throws Exception {
        String token = token("dora");
        server.users().disable("dora");
        Client client = Client.connect(server.port(), token);
        assertEquals(Wire.CLOSE_EXPIRED, client.closeCode());
        assertEquals("revoked", client.closeReason);
        assertEquals(LocalTokens.Status.REVOKED, links.tokens().verify(token).status());

        String orphan = links.tokens().issue(new Principal("nobody"), Duration.ofHours(1)).token();
        assertEquals(Wire.CLOSE_EXPIRED, Client.connect(server.port(), orphan).closeCode());
    }

    /** 需求 1.2：连上、发 {@code hello}，收到 {@code welcome}，状态里有电脑说的内容。 */
    @Test
    void aValidTokenAndHelloAreWelcomed() throws Exception {
        Client client = Client.connect(server.port(), token("walt"));
        client.send(HELLO);
        Welcome welcome = (Welcome) client.next();
        assertEquals("walt", welcome.user());
        LocalLinks.Status status = links.status(new Principal("walt")).orElseThrow();
        assertEquals(HELLO, status.hello());
        assertEquals(welcome.expiresAt(), status.expiresAt());
        client.socket.abort();
    }

    /** 协议版本不对是 4400；第一条不是 {@code hello} 关掉。 */
    @Test
    void theFirstMessageMustBeAHelloOfThisProtocol() throws Exception {
        Client old = Client.connect(server.port(), token("olga"));
        old.send(new Hello(Wire.PROTOCOL + 1, "future", HELLO.device(), HELLO.roots(), false, HELLO.shell(), true));
        assertEquals(Wire.CLOSE_PROTOCOL, old.closeCode());

        Client rude = Client.connect(server.port(), token("olga"));
        rude.send(new Result("r-1", "hi", false, List.of()));
        assertEquals(1008, rude.closeCode());
        assertEquals(Optional.empty(), links.status(new Principal("olga")));
    }

    /** 设计“LocalEndpoint”：5 秒内没有 {@code hello} 就关。 */
    @Test
    void aSilentConnectionIsClosedAfterFiveSeconds() throws Exception {
        long started = System.nanoTime();
        Client silent = Client.connect(server.port(), token("sam"));
        assertEquals(1008, silent.closeCode(Duration.ofSeconds(10)));
        long waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue(waited >= 4_500 && waited < 9_000, waited + " ms");
    }

    /** 需求 1.6：第二条连接让第一条收到 {@code bye replaced} 和 4409。 */
    @Test
    void aSecondConnectionReplacesTheFirst() throws Exception {
        Client first = Client.connect(server.port(), token("rob"));
        first.send(HELLO);
        assertInstanceOf(Welcome.class, first.next());
        Client second = Client.connect(server.port(), token("rob"));
        second.send(HELLO);
        assertInstanceOf(Welcome.class, second.next());
        assertEquals(new Bye(Bye.Reason.REPLACED), first.next());
        assertEquals(Wire.CLOSE_REPLACED, first.closeCode());
        assertTrue(links.status(new Principal("rob")).isPresent());
        second.socket.abort();
    }

    /** 需求 1.4：到期那一刻发 {@code bye expired}、4403 关掉，远在 5 秒之内。 */
    @Test
    void theConnectionEndsAtTheExpiry() throws Exception {
        Client client = Client.connect(server.port(), tokenExpiringIn("eve", Duration.ofMillis(1500)));
        client.send(HELLO);
        assertInstanceOf(Welcome.class, client.next());
        long started = System.nanoTime();
        assertEquals(new Bye(Bye.Reason.EXPIRED), client.next(Duration.ofSeconds(5)));
        assertEquals(Wire.CLOSE_EXPIRED, client.closeCode());
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(3));
        assertEquals(Optional.empty(), links.status(new Principal("eve")));
    }

    /** 撤销：连着的电脑收到 {@code bye revoked}、4403。 */
    @Test
    void revokingClosesTheConnection() throws Exception {
        Client client = Client.connect(server.port(), token("ray"));
        client.send(HELLO);
        assertInstanceOf(Welcome.class, client.next());
        links.revoke(new Principal("ray"));
        assertEquals(new Bye(Bye.Reason.REVOKED), client.next());
        assertEquals(Wire.CLOSE_EXPIRED, client.closeCode());
    }

    /** 一次调用走真的 WebSocket，结果超过 Tomcat 默认的 8 KB 帧也收得下。 */
    @Test
    void aCallTravelsBothWays() throws Exception {
        Client client = Client.connect(server.port(), token("cal"));
        client.send(HELLO);
        client.next();
        CompletableFuture<LocalResult> result = callAsync("cal", Duration.ofSeconds(10));
        Call call = (Call) client.next();
        assertEquals("cal", call.user());
        assertEquals(ARGS, call.arguments());
        String big = "x".repeat(1_000_000);
        client.send(new Result(call.id(), big, false, List.of()));
        assertEquals(LocalResult.ok(big), result.get(5, TimeUnit.SECONDS));
        client.socket.abort();
    }

    /** 需求 5.4、L8：调用执行到一半电脑断开，这次调用得到“结果不确定”。 */
    @Test
    void aDisconnectInTheMiddleOfACallIsReported() throws Exception {
        Client client = Client.connect(server.port(), token("dan"));
        client.send(HELLO);
        client.next();
        CompletableFuture<LocalResult> result = callAsync("dan", Duration.ofSeconds(30));
        assertInstanceOf(Call.class, client.next());
        client.socket.abort();
        assertEquals(LocalResult.error(LocalLinks.DISCONNECTED), result.get(5, TimeUnit.SECONDS));
        assertEquals(LocalResult.error(LocalLinks.NOT_CONNECTED),
                links.call(new Principal("dan"), "s-1", "read_file", ARGS, Duration.ofSeconds(1)));
    }

    /** 放行的只有握手路径，其他接口照旧要登录。 */
    @Test
    void theRestOfTheApiStillNeedsALogin() throws Exception {
        HttpResponse<String> sessions = server.get(null, "/api/sessions");
        assertEquals(401, sessions.statusCode());
    }

    private static CompletableFuture<LocalResult> callAsync(String user, Duration timeout) {
        CompletableFuture<LocalResult> result = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                result.complete(links.call(new Principal(user), "s-1", "read_file", ARGS, timeout));
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    /** JDK 的 WebSocket 客户端，收到的消息排进队列。 */
    static final class Client implements WebSocket.Listener {

        final BlockingQueue<Message> messages = new LinkedBlockingQueue<>();
        final CompletableFuture<Integer> closed = new CompletableFuture<>();
        volatile String closeReason;
        private final StringBuilder partial = new StringBuilder();
        WebSocket socket;

        static Client connect(int port, String token) {
            return connectRaw(port, token == null ? null : token.startsWith("Basic") ? token : "Bearer " + token);
        }

        static Client connectRaw(int port, String authorization) {
            Client client = new Client();
            WebSocket.Builder builder = HttpClient.newHttpClient().newWebSocketBuilder();
            if (authorization != null) {
                builder.header("Authorization", authorization);
            }
            client.socket = builder.buildAsync(URI.create("ws://127.0.0.1:" + port + LocalEndpoint.PATH), client).join();
            return client;
        }

        void send(Message message) {
            socket.sendText(Wire.encode(message), true).join();
        }

        Message next() throws InterruptedException {
            return next(Duration.ofSeconds(3));
        }

        Message next(Duration wait) throws InterruptedException {
            Message message = messages.poll(wait.toMillis(), TimeUnit.MILLISECONDS);
            if (message == null) {
                throw new AssertionError("no message within " + wait);
            }
            return message;
        }

        int closeCode() throws Exception {
            return closeCode(Duration.ofSeconds(3));
        }

        int closeCode(Duration wait) throws Exception {
            return closed.get(wait.toMillis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                messages.add(Wire.decode(partial.toString()));
                partial.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closeReason = reason;
            closed.complete(statusCode);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closed.completeExceptionally(error);
        }
    }
}
