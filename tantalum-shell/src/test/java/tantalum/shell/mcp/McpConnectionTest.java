package tantalum.shell.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tantalum.shell.mcp.TestMcpServer.Definition;
import tantalum.shell.plugin.ToolOutput;

@Timeout(60)
class McpConnectionTest {

    private static final McpConnection.Policy FAST = new McpConnection.Policy(Duration.ofMillis(20), Duration.ofMillis(100), 5,
            Duration.ofSeconds(60), Duration.ofMillis(100), Duration.ofMillis(100));

    private final AtomicInteger slowStarted = new AtomicInteger();
    private final List<List<String>> published = new CopyOnWriteArrayList<>();
    private TestMcpServer server;
    private McpConnection connection;

    @BeforeEach
    void start() throws Exception {
        server = new TestMcpServer(
                new Definition("read", true, args -> TestMcpServer.text("read " + args.getOrDefault("x", ""))),
                new Definition("bad", true, args -> TestMcpServer.error("boom")),
                new Definition("write", null, args -> TestMcpServer.text("wrote")),
                new Definition("slow", true, args -> {
                    slowStarted.incrementAndGet();
                    sleep(20_000);
                    return TestMcpServer.text("late");
                }));
    }

    @AfterEach
    void stop() throws Exception {
        if (connection != null) {
            connection.close();
        }
        server.close();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        }
    }

    private static McpSettings.Server settings(String url, String token, Duration timeout) {
        return new McpSettings.Server("t", URI.create(url), token == null ? McpSettings.Auth.NONE : McpSettings.Auth.SERVICE, token,
                Set.of(), Set.of(), 4, timeout, 1024, 64);
    }

    private McpConnection connect(String url, String token, Duration timeout) {
        connection = new McpConnection(settings(url, token, timeout), FAST,
                tools -> published.add(tools.stream().map(McpSchema.Tool::name).toList()));
        return connection;
    }

    private McpConnection ready() {
        McpConnection opened = connect(server.url(), null, Duration.ofSeconds(5));
        await(() -> opened.state() == McpConnection.State.READY);
        return opened;
    }

    private static void await(BooleanSupplier condition) {
        for (int i = 0; i < 1500 && !condition.getAsBoolean(); i++) {
            sleep(10);
        }
        assertTrue(condition.getAsBoolean());
    }

    @Test
    void discoversToolsWithAnnotations() {
        McpConnection opened = ready();
        assertEquals(List.of("read", "bad", "write", "slow"), opened.tools().stream().map(McpSchema.Tool::name).toList());
        assertEquals(Boolean.TRUE, opened.tools().getFirst().annotations().readOnlyHint());
        assertEquals(null, opened.tools().get(2).annotations().readOnlyHint());
        assertEquals(List.of("read", "bad", "write", "slow"), published.getFirst());
    }

    @Test
    void callsToolsAndMapsErrors() throws Exception {
        McpConnection opened = ready();
        ToolOutput read = opened.call("read", Map.of("x", "1"), true);
        assertFalse(read.error());
        assertEquals("read 1", read.text());
        assertTrue(opened.call("bad", Map.of(), true).error());
        ToolOutput unknown = opened.call("nope", Map.of(), true);
        assertTrue(unknown.error());
        assertEquals(McpConnection.State.READY, opened.state(), "a protocol error is not a broken connection");
    }

    @Test
    void sendsTheServiceTokenAndNeverFollowsRedirects() throws Exception {
        McpConnection opened = connect(server.url(), "s3cret", Duration.ofSeconds(5));
        await(() -> opened.state() == McpConnection.State.READY);
        assertEquals(Set.of("Bearer s3cret"), server.authorizations);
        opened.close();

        McpConnection redirected = connect(server.redirectUrl(), "s3cret", Duration.ofSeconds(5));
        sleep(300);
        assertTrue(redirected.tools().isEmpty());
        assertTrue(redirected.state() != McpConnection.State.READY, redirected.state().toString());
    }

    @Test
    void interruptReturnsAtOnceAndLeavesTheConnectionUsable() throws Exception {
        McpConnection opened = ready();
        Throwable[] thrown = new Throwable[1];
        Thread caller = Thread.ofVirtual().start(() -> {
            try {
                opened.call("slow", Map.of(), true);
            } catch (Throwable failure) {
                thrown[0] = failure;
            }
        });
        await(() -> slowStarted.get() == 1);
        caller.interrupt();
        caller.join(3000);
        assertFalse(caller.isAlive());
        assertTrue(thrown[0] instanceof InterruptedException, String.valueOf(thrown[0]));
        assertFalse(opened.call("read", Map.of(), true).error());
    }

    @Test
    void timeoutSaysTheOutcomeIsUnknownAndKeepsTheConnection() throws Exception {
        McpConnection opened = connect(server.url(), null, Duration.ofMillis(300));
        await(() -> opened.state() == McpConnection.State.READY);
        ToolOutput output = opened.call("slow", Map.of(), false);
        assertTrue(output.error());
        assertTrue(output.text().contains("outcome is unknown"), output.text());
        assertEquals(McpConnection.State.READY, opened.state());
    }

    @Test
    void recoversAfterServerRestartWithAFreshClient() throws Exception {
        McpConnection opened = ready();
        server.stop();
        await(() -> opened.state() != McpConnection.State.READY);
        assertFalse(opened.tools().isEmpty(), "tools stay registered during an outage");
        ToolOutput down = opened.call("read", Map.of(), false);
        assertTrue(down.error());
        server.start();
        await(() -> opened.state() == McpConnection.State.READY);
        assertFalse(opened.call("read", Map.of(), true).error());
    }

    @Test
    void afterAnUnnoticedRestartReadsRetryOnceAndWritesDoNot() throws Exception {
        // 探活间隔很长，连接还不知道会话已失效，第一次调用才发现。
        McpConnection.Policy noPing = new McpConnection.Policy(Duration.ofMillis(20), Duration.ofMillis(100), 5,
                Duration.ofSeconds(60), Duration.ofMillis(100), Duration.ofMinutes(10));
        connection = new McpConnection(settings(server.url(), null, Duration.ofSeconds(5)), noPing, tools -> { });
        McpConnection opened = connection;
        await(() -> opened.state() == McpConnection.State.READY);

        server.stop();
        server.start();
        server.calls.clear();
        ToolOutput write = opened.call("write", Map.of(), false);
        assertTrue(write.error(), write.text());
        assertTrue(write.text().contains("outcome of this call is unknown"), write.text());
        assertEquals(List.of(), server.calls, "a write is not retried");
        await(() -> opened.state() == McpConnection.State.READY);

        server.stop();
        server.start();
        ToolOutput read = opened.call("read", Map.of(), true);
        assertFalse(read.error(), read.text());
        assertEquals(List.of("read"), server.calls, "the read ran once, on the new connection");
    }

    @Test
    void repeatedFailuresRemoveToolsThenRecoverWhenTheServerReturns() throws Exception {
        McpConnection opened = ready();
        server.stop();
        await(() -> opened.state() == McpConnection.State.FAILED);
        await(() -> opened.tools().isEmpty());
        assertEquals(List.of(), published.getLast());
        server.start();
        await(() -> opened.state() == McpConnection.State.READY);
        assertFalse(opened.tools().isEmpty());
    }

    @Test
    void rejectedCredentialsAreReportedAsUnauthorized() throws Exception {
        server.rejectAll = true;
        McpConnection opened = connect(server.url(), "wrong", Duration.ofSeconds(2));
        await(() -> opened.state() == McpConnection.State.UNAUTHORIZED);
        assertTrue(opened.tools().isEmpty());
        server.rejectAll = false;
        await(() -> opened.state() == McpConnection.State.READY);
    }

    @Test
    void listChangedUpdatesTheTools() {
        McpConnection opened = ready();
        server.addTool(new Definition("late", true, args -> TestMcpServer.text("late")));
        await(() -> opened.tools().stream().anyMatch(tool -> tool.name().equals("late")));
    }

    @Test
    void closeStopsEverything() {
        McpConnection opened = ready();
        opened.close();
        await(() -> opened.state() == McpConnection.State.CLOSED);
        sleep(300);
        assertEquals(McpConnection.State.CLOSED, opened.state(), "a closed connection does not come back");
    }
}
