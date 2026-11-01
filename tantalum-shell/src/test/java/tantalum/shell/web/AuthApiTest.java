package tantalum.shell.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.model.ModelOutcome;

class AuthApiTest {

    @TempDir
    Path temp;

    private TestServer server;

    @BeforeEach
    void start() throws Exception {
        server = new TestServer(temp.resolve("data"), Files.createDirectory(temp.resolve("workspace")),
                (prompt, chunks) -> new ModelOutcome.Interrupted(""), "--tantalum.sse-heartbeat=100ms");
        server.users().add("alice", "alice-password");
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private static String cookieOf(HttpResponse<String> response) {
        return response.headers().firstValue("set-cookie").orElseThrow().split(";")[0];
    }

    private HttpResponse<String> getWith(String cookie, String path, String... headers) throws Exception {
        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + server.port() + path));
        if (cookie != null) {
            request.header("Cookie", cookie);
        }
        if (headers.length > 0) {
            request.headers(headers);
        }
        return server.http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void loginSucceedsAndSetsAHardenedCookie() throws Exception {
        HttpResponse<String> login = server.attemptLogin("alice", "alice-password", null);
        assertEquals(200, login.statusCode(), login.body());
        String cookie = login.headers().firstValue("set-cookie").orElseThrow().toLowerCase();
        assertTrue(cookie.contains("httponly"), cookie);
        assertTrue(cookie.contains("samesite=strict"), cookie);
        assertTrue(getWith(cookieOf(login), "/api/auth/me").body().contains("alice"));
    }

    @Test
    void wrongPasswordAndUnknownUserGiveTheSameAnswer() throws Exception {
        HttpResponse<String> wrong = server.attemptLogin("alice", "nope", null);
        HttpResponse<String> unknown = server.attemptLogin("nobody", "nope", null);
        assertEquals(401, wrong.statusCode());
        assertEquals(401, unknown.statusCode());
        assertEquals(wrong.body(), unknown.body());
    }

    @Test
    void apiRequiresLoginIncludingEvents() throws Exception {
        assertEquals(401, getWith(null, "/api/auth/me").statusCode());
        assertEquals(401, getWith(null, "/api/events").statusCode());
        assertEquals(401, getWith(null, "/api/sessions/s/runs").statusCode());
        assertEquals(401, server.post(null, "/api/sessions/s/submissions", "{}").statusCode());
    }

    @Test
    void xPrincipalHeaderIsIgnored() throws Exception {
        assertEquals(401, getWith(null, "/api/auth/me", "X-Principal", "alice").statusCode());
        String cookie = cookieOf(server.attemptLogin("alice", "alice-password", null));
        HttpResponse<String> me = getWith(cookie, "/api/auth/me", "X-Principal", "bob");
        assertTrue(me.body().contains("alice"), me.body());
        assertFalse(me.body().contains("bob"), me.body());
    }

    @Test
    void loginChangesTheSessionId() throws Exception {
        String first = cookieOf(server.attemptLogin("alice", "alice-password", null));
        String second = cookieOf(server.attemptLogin("alice", "alice-password", first));
        assertNotEquals(first, second);
        assertEquals(401, getWith(first, "/api/auth/me").statusCode());
    }

    @Test
    void logoutInvalidatesTheSession() throws Exception {
        String cookie = cookieOf(server.attemptLogin("alice", "alice-password", null));
        var logout = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + server.port() + "/api/auth/logout"))
                .header("Cookie", cookie).POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build();
        assertEquals(200, server.http.send(logout, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(401, getWith(cookie, "/api/auth/me").statusCode());
    }

    private java.io.InputStream openEvents(String cookie) throws Exception {
        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + server.port() + "/api/events"))
                .header("Cookie", cookie).GET().build();
        HttpResponse<java.io.InputStream> response = server.http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode());
        return response.body();
    }

    /** 读到流结束就返回。超过时限说明连接没有被服务端断开。 */
    private static void assertStreamEnds(java.io.InputStream stream) throws Exception {
        Thread reader = Thread.ofVirtual().start(() -> {
            try {
                stream.readAllBytes();
            } catch (java.io.IOException ended) {
                // 连接被断开也算结束。
            }
        });
        reader.join(java.time.Duration.ofSeconds(30));
        assertFalse(reader.isAlive(), "SSE connection stayed open");
    }

    @Test
    void logoutDisconnectsOpenEventStreams() throws Exception {
        String cookie = cookieOf(server.attemptLogin("alice", "alice-password", null));
        java.io.InputStream stream = openEvents(cookie);
        Thread.sleep(200);
        var logout = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + server.port() + "/api/auth/logout"))
                .header("Cookie", cookie).POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build();
        server.http.send(logout, HttpResponse.BodyHandlers.ofString());
        assertStreamEnds(stream);
    }

    @Test
    void disablingAUserDisconnectsOpenEventStreams() throws Exception {
        java.io.InputStream stream = openEvents(cookieOf(server.attemptLogin("alice", "alice-password", null)));
        Thread.sleep(200);
        server.users().disable("alice");
        assertStreamEnds(stream);
    }

    /** 超长输入在进 Argon2 和限速表之前就被拒。 */
    @Test
    void oversizedCredentialsAreRejectedBeforeVerification() throws Exception {
        assertEquals(400, server.attemptLogin("a".repeat(65), "alice-password", null).statusCode());
        assertEquals(400, server.attemptLogin("alice", "p".repeat(1025), null).statusCode());
        assertEquals(400, server.attemptLogin("alice", "p".repeat(9000), null).statusCode());
        assertEquals(400, server.attemptLogin("", "alice-password", null).statusCode());
        assertEquals(200, server.attemptLogin("alice", "alice-password", null).statusCode());
    }

    @Test
    void runOrderToleratesOddRunIds() {
        assertEquals(12, ApiController.runOrder(new tantalum.value.id.RunId("run-12")));
        assertEquals(-1, ApiController.runOrder(new tantalum.value.id.RunId("legacy")));
        assertEquals(-1, ApiController.runOrder(new tantalum.value.id.RunId("run-x")));
    }

    @Test
    void cookieMustBeSecureWhenNotBoundToLoopback() {
        assertThrows(IllegalStateException.class, () -> tantalum.shell.auth.SecurityConfig.requireSecureCookie("0.0.0.0", false));
        // 没设 server.address 时 Spring 绑定全部网卡，不算回环。
        assertThrows(IllegalStateException.class, () -> tantalum.shell.auth.SecurityConfig.requireSecureCookie(null, false));
        assertThrows(IllegalStateException.class, () -> tantalum.shell.auth.SecurityConfig.requireSecureCookie("127.evil.example", false));
        assertThrows(IllegalStateException.class, () -> tantalum.shell.auth.SecurityConfig.requireSecureCookie("10.0.0.5", false));
        tantalum.shell.auth.SecurityConfig.requireSecureCookie("10.0.0.5", true);
        tantalum.shell.auth.SecurityConfig.requireSecureCookie("127.0.0.1", false);
        tantalum.shell.auth.SecurityConfig.requireSecureCookie("::1", false);
        tantalum.shell.auth.SecurityConfig.requireSecureCookie("localhost", false);
    }

    @Test
    void disablingAUserEndsTheirSessionImmediately() throws Exception {
        String cookie = cookieOf(server.attemptLogin("alice", "alice-password", null));
        assertEquals(200, getWith(cookie, "/api/auth/me").statusCode());
        server.users().disable("alice");
        assertEquals(401, getWith(cookie, "/api/auth/me").statusCode());
        assertEquals(401, server.attemptLogin("alice", "alice-password", null).statusCode());
    }

    @Test
    void repeatedFailuresAreThrottled() throws Exception {
        int status = 0;
        for (int i = 0; i < 10 && status != 429; i++) {
            status = server.attemptLogin("alice", "wrong", null).statusCode();
        }
        assertEquals(429, status);
        assertEquals(429, server.attemptLogin("alice", "alice-password", null).statusCode());
    }

    @Test
    void crossOriginWritesAreRejected() throws Exception {
        String cookie = cookieOf(server.attemptLogin("alice", "alice-password", null));
        var evil = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + server.port() + "/api/sessions/s/submissions"))
                .header("Cookie", cookie).header("Origin", "https://evil.example").header("content-type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"requestId\":\"r\",\"mode\":\"prompt\",\"text\":\"hi\"}")).build();
        assertEquals(403, server.http.send(evil, HttpResponse.BodyHandlers.ofString()).statusCode());
        var same = java.net.http.HttpRequest.newBuilder(evil.uri())
                .header("Cookie", cookie).header("Origin", "http://127.0.0.1:" + server.port()).header("content-type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"requestId\":\"r\",\"mode\":\"prompt\",\"text\":\"hi\"}")).build();
        assertEquals(200, server.http.send(same, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void passwordNeverAppearsInResponses() throws Exception {
        assertFalse(server.attemptLogin("alice", "alice-password", null).body().contains("alice-password"));
    }
}
