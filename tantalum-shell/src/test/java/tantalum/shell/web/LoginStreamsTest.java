package tantalum.shell.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.auth.UserStore;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.ModelOutcome;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;

/** 任务 37、38：SSE 按登录断开；过期、停用、改密码都在一个心跳内断开，包括服务之外的修改。 */
class LoginStreamsTest {

    private static final String HEARTBEAT = "--tantalum.sse-heartbeat=200ms";
    /** 一个心跳周期加上检查和断开的余量。 */
    private static final long WITHIN = 1_500;

    @TempDir
    Path temp;

    private final ModelClient model = (prompt, chunks) ->
            new ModelOutcome.Replied(List.of(new TextBlock("ok")), StopReason.END_TURN, "m", null);

    private TestServer server(String... extra) throws Exception {
        Files.createDirectories(temp.resolve("workspace"));
        String[] args = java.util.stream.Stream.concat(java.util.stream.Stream.of(HEARTBEAT), java.util.stream.Stream.of(extra)).toArray(String[]::new);
        return new TestServer(temp.resolve("data"), temp.resolve("workspace"), model, args);
    }

    /** 一次独立的登录，有自己的 Cookie。 */
    private static String login(TestServer server, String user) throws Exception {
        if (server.users().status(user).isEmpty()) {
            server.users().add(user, TestServer.PASSWORD);
        }
        HttpResponse<String> response = server.attemptLogin(user, TestServer.PASSWORD, null);
        assertEquals(200, response.statusCode(), response.body());
        return response.headers().firstValue("set-cookie").orElseThrow().split(";")[0];
    }

    /** 一条 SSE 连接：在后台读行，连接结束时 {@code ended} 归零。 */
    private static final class Stream {
        final List<String> lines = new CopyOnWriteArrayList<>();
        final CountDownLatch ended = new CountDownLatch(1);

        Stream(TestServer server, String cookie) throws Exception {
            HttpResponse<InputStream> response = server.http.send(
                    server.request(null, "/api/events").header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, response.statusCode());
            Thread.ofVirtual().start(() -> {
                try (BufferedReader in = new BufferedReader(new InputStreamReader(response.body()))) {
                    in.lines().forEach(lines::add);
                } catch (Exception closed) {
                    // 连接断开。
                } finally {
                    ended.countDown();
                }
            });
        }

        boolean endsWithin(long millis) throws InterruptedException {
            return ended.await(millis, TimeUnit.MILLISECONDS);
        }
    }

    private static HttpResponse<String> get(TestServer server, String cookie, String path) throws Exception {
        return server.http.send(server.request(null, path).header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void loggingOutEndsOnlyThatLoginsStream() throws Exception {
        try (TestServer server = server()) {
            String laptop = login(server, "alice");
            String phone = login(server, "alice");
            Stream laptopStream = new Stream(server, laptop);
            Stream phoneStream = new Stream(server, phone);
            Thread.sleep(300);
            HttpResponse<String> out = server.http.send(server.request(null, "/api/auth/logout").header("Cookie", laptop)
                    .POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, out.statusCode());
            assertTrue(laptopStream.endsWithin(WITHIN), "the logged-out stream must end");
            assertFalse(phoneStream.endsWithin(300), "the other login keeps its stream");

            HttpResponse<String> submitted = server.http.send(server.request(null, "/api/sessions/s1/submissions").header("Cookie", phone)
                    .header("content-type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"requestId\":\"r1\",\"mode\":\"prompt\",\"text\":\"hi\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, submitted.statusCode(), submitted.body());
            for (int i = 0; i < 100 && phoneStream.lines.stream().noneMatch(line -> line.contains("\"session\":\"s1\"")); i++) {
                Thread.sleep(20);
            }
            assertTrue(phoneStream.lines.stream().anyMatch(line -> line.contains("\"session\":\"s1\"")), "the other login still receives events");
            assertEquals(401, get(server, laptop, "/api/sessions").statusCode());
            assertEquals(200, get(server, phone, "/api/sessions").statusCode());
        }
    }

    /** 命令行在服务之外改密码：另开一个用户库连接修改，两次登录的连接都在一个心跳内断开，旧 Cookie 的请求也被拒绝。 */
    @Test
    void aPasswordChangedOutsideTheServerEndsEveryOldLogin() throws Exception {
        try (TestServer server = server()) {
            String laptop = login(server, "alice");
            String phone = login(server, "alice");
            Stream laptopStream = new Stream(server, laptop);
            Stream phoneStream = new Stream(server, phone);
            Thread.sleep(300);
            assertFalse(laptopStream.endsWithin(0));
            try (UserStore outside = UserStore.open(temp.resolve("data"))) {
                outside.resetPassword("alice", "a-new-password");
            }
            assertTrue(laptopStream.endsWithin(WITHIN));
            assertTrue(phoneStream.endsWithin(WITHIN));
            assertEquals(401, get(server, laptop, "/api/sessions").statusCode());
            HttpResponse<String> fresh = server.attemptLogin("alice", "a-new-password", null);
            assertEquals(200, fresh.statusCode());
            String cookie = fresh.headers().firstValue("set-cookie").orElseThrow().split(";")[0];
            assertEquals(200, get(server, cookie, "/api/sessions").statusCode(), "a login after the change is valid");
        }
    }

    @Test
    void aUserDisabledOutsideTheServerLosesTheStream() throws Exception {
        try (TestServer server = server()) {
            String alice = login(server, "alice");
            String bob = login(server, "bob");
            Stream aliceStream = new Stream(server, alice);
            Stream bobStream = new Stream(server, bob);
            Thread.sleep(300);
            try (UserStore outside = UserStore.open(temp.resolve("data"))) {
                outside.disable("alice");
            }
            assertTrue(aliceStream.endsWithin(WITHIN));
            assertFalse(bobStream.endsWithin(300), "other users are not affected");
        }
    }

    /** 连接本身不刷新会话；没有其他请求时，会话到期后一个心跳内断开，不等容器的后台清理。 */
    @Test
    void anExpiredLoginLosesTheStreamWithinAHeartbeat() throws Exception {
        // Tomcat 的会话超时以分钟为单位，配置里的 2s 会变成 1 分钟，所以直接给新会话设 2 秒。
        Files.createDirectories(temp.resolve("workspace"));
        try (TestServer server = new TestServer(temp.resolve("data"), temp.resolve("workspace"), model,
                context -> context.registerBean("shortSessions", jakarta.servlet.http.HttpSessionListener.class,
                        () -> new jakarta.servlet.http.HttpSessionListener() {
                            @Override
                            public void sessionCreated(jakarta.servlet.http.HttpSessionEvent event) {
                                event.getSession().setMaxInactiveInterval(2);
                            }
                        }), HEARTBEAT)) {
            String alice = login(server, "alice");
            long opened = System.currentTimeMillis();
            Stream stream = new Stream(server, alice);
            assertFalse(stream.endsWithin(1_000), "still within the timeout");
            assertTrue(stream.endsWithin(2_000 + WITHIN), "ended " + (System.currentTimeMillis() - opened) + " ms after opening");
        }
    }
}
