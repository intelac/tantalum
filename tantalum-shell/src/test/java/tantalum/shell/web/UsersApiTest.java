package tantalum.shell.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.model.ModelOutcome;

/** ops-hardening-2 需求 2：管理员在接口上管用户；自己改密码。 */
class UsersApiTest {

    @TempDir
    Path temp;

    private TestServer server;
    private String root;

    @BeforeEach
    void start() throws Exception {
        server = new TestServer(temp.resolve("data"), Files.createDirectory(temp.resolve("workspace")),
                (prompt, chunks) -> new ModelOutcome.Interrupted(""), "--tantalum.sse-heartbeat=100ms");
        server.users().add("root", "root-password", true);
        server.users().add("alice", "alice-password");
        root = cookie("root", "root-password");
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private String cookie(String user, String password) throws Exception {
        HttpResponse<String> login = server.attemptLogin(user, password, null);
        assertEquals(200, login.statusCode(), login.body());
        return login.headers().firstValue("set-cookie").orElseThrow().split(";")[0];
    }

    private HttpResponse<String> send(String cookie, String method, String path, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .header("Cookie", cookie).header("Content-Type", "application/json")
                .header("Origin", "http://127.0.0.1:" + server.port());
        request = body == null ? request.method(method, HttpRequest.BodyPublishers.noBody())
                : request.method(method, HttpRequest.BodyPublishers.ofString(body));
        return server.http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void adminListsCreatesUpdatesAndResets() throws Exception {
        HttpResponse<String> list = send(root, "GET", "/api/users", null);
        assertEquals(200, list.statusCode());
        assertTrue(list.body().contains("\"name\":\"root\",\"admin\":true,\"disabled\":false"), list.body());
        assertTrue(list.body().contains("\"name\":\"alice\",\"admin\":false"), list.body());

        // 新建：名字规则、重复
        assertEquals(200, send(root, "POST", "/api/users", "{\"name\":\"bob\",\"password\":\"bob-password\",\"admin\":false}").statusCode());
        assertEquals(200, cookie("bob", "bob-password").length() > 0 ? 200 : 0);
        assertTrue(send(root, "POST", "/api/users", "{\"name\":\"bob\",\"password\":\"x-password\"}").body().contains("user/exists"));
        assertTrue(send(root, "POST", "/api/users", "{\"name\":\"Bad Name!\",\"password\":\"x-password\"}").body().contains("user/bad-name"));
        assertTrue(send(root, "POST", "/api/users", "{\"name\":\"nul\",\"password\":\"x-password\"}").body().contains("user/reserved-name"));
        assertEquals(400, send(root, "POST", "/api/users", "{\"name\":\"carol\"}").statusCode(), "password required");
        HttpResponse<String> weak = send(root, "POST", "/api/users", "{\"name\":\"carol\",\"password\":\"1234567\"}");
        assertEquals(400, weak.statusCode());
        assertTrue(weak.body().contains("auth/password-too-short"), "review-fixes 需求 11.13: " + weak.body());

        // 设管理员、取消；不存在
        assertTrue(send(root, "PUT", "/api/users/alice", "{\"admin\":true}").body().contains("\"admin\":true"));
        assertTrue(send(root, "PUT", "/api/users/alice", "{\"admin\":false}").body().contains("\"admin\":false"));
        assertEquals(404, send(root, "PUT", "/api/users/nobody", "{\"admin\":true}").statusCode());
        assertEquals(400, send(root, "PUT", "/api/users/alice", "{}").statusCode());

        // 对自己：不能停用、不能取消
        assertTrue(send(root, "PUT", "/api/users/root", "{\"disabled\":true}").body().contains("user/self"));
        assertTrue(send(root, "PUT", "/api/users/root", "{\"admin\":false}").body().contains("user/self"));
        // 角色每个请求从表里读：alice 成为管理员后能取消 root；root 随即就是普通用户，再碰用户接口是 403
        send(root, "PUT", "/api/users/alice", "{\"admin\":true}");
        String alice = cookie("alice", "alice-password");
        assertEquals(200, send(alice, "PUT", "/api/users/root", "{\"admin\":false}").statusCode());
        assertEquals(403, send(root, "GET", "/api/users", null).statusCode());
        // 现在 alice 是唯一的管理员：对自己是 user/self（最后一个管理员的 409 在存储层和命令行测）
        assertTrue(send(alice, "PUT", "/api/users/alice", "{\"admin\":false}").body().contains("user/self"));
    }

    /** 需求 2.5：管理员重置密码或停用，那个人的登录立即失效。 */
    @Test
    void resetAndDisableSignTheUserOut() throws Exception {
        String alice = cookie("alice", "alice-password");
        assertEquals(200, send(alice, "GET", "/api/auth/me", null).statusCode());
        assertEquals(200, send(root, "PUT", "/api/users/alice/password", "{\"password\":\"new-password\"}").statusCode());
        assertEquals(401, send(alice, "GET", "/api/auth/me", null).statusCode(), "old login is out");
        assertEquals(401, server.attemptLogin("alice", "alice-password", null).statusCode());
        alice = cookie("alice", "new-password");

        assertTrue(send(root, "PUT", "/api/users/alice", "{\"disabled\":true}").body().contains("\"disabled\":true"));
        assertEquals(401, send(alice, "GET", "/api/auth/me", null).statusCode(), "disabled users are out");
        assertEquals(401, server.attemptLogin("alice", "new-password", null).statusCode());
        assertTrue(send(root, "PUT", "/api/users/alice", "{\"disabled\":false}").body().contains("\"disabled\":false"));
        assertEquals(200, server.attemptLogin("alice", "new-password", null).statusCode());
        assertEquals(400, send(root, "PUT", "/api/users/alice/password", "{}").statusCode());
        assertEquals(404, send(root, "PUT", "/api/users/nobody/password", "{\"password\":\"x-password\"}").statusCode());
        assertTrue(send(root, "PUT", "/api/users/alice/password", "{\"password\":\"short\"}").body().contains("auth/password-too-short"));
    }

    /** 需求 2.6、D3：自己改密码要当前密码；改后别处失效、当前保留。 */
    @Test
    void aUserChangesTheirOwnPassword() throws Exception {
        String laptop = cookie("alice", "alice-password");
        String phone = cookie("alice", "alice-password");
        HttpResponse<String> wrong = send(laptop, "PUT", "/api/auth/password", "{\"current\":\"nope\",\"next\":\"n2-password\"}");
        assertEquals(401, wrong.statusCode());
        assertTrue(wrong.body().contains("auth/invalid-credentials"), wrong.body());
        assertEquals(400, send(laptop, "PUT", "/api/auth/password", "{\"current\":\"alice-password\",\"next\":\"\"}").statusCode());
        HttpResponse<String> weak = send(laptop, "PUT", "/api/auth/password", "{\"current\":\"alice-password\",\"next\":\"alice-2\"}");
        assertEquals(400, weak.statusCode());
        assertTrue(weak.body().contains("auth/password-too-short"), weak.body());

        assertEquals(200, send(laptop, "PUT", "/api/auth/password", "{\"current\":\"alice-password\",\"next\":\"alice-22\"}").statusCode());
        assertEquals(200, send(laptop, "GET", "/api/auth/me", null).statusCode(), "the login that changed it stays");
        assertEquals(401, send(phone, "GET", "/api/auth/me", null).statusCode(), "other logins are out");
        assertEquals(401, server.attemptLogin("alice", "alice-password", null).statusCode());
        assertEquals(200, server.attemptLogin("alice", "alice-22", null).statusCode());
    }

    @Test
    void nonAdminsAreRefused() throws Exception {
        String alice = cookie("alice", "alice-password");
        assertEquals(403, send(alice, "GET", "/api/users", null).statusCode());
        assertEquals(403, send(alice, "POST", "/api/users", "{\"name\":\"x\",\"password\":\"y\"}").statusCode());
        assertEquals(403, send(alice, "PUT", "/api/users/root/password", "{\"password\":\"y\"}").statusCode());
    }
}
