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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.ModelOutcome;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;

/** 需求 9 第 11 条：两个真实账号之间互相看不到对方的任何内容。 */
class MultiUserTest {

    private static final String SECRET = "alice-private-answer";

    @TempDir
    Path temp;

    private TestServer server;

    private final ModelClient model = (prompt, chunks) -> {
        chunks.onText(SECRET);
        return new ModelOutcome.Replied(List.of(new TextBlock(SECRET)), StopReason.END_TURN, "m", null);
    };

    @BeforeEach
    void start() throws Exception {
        server = new TestServer(temp.resolve("data"), Files.createDirectory(temp.resolve("workspace")), model);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private String finishedRun(String user, String session) throws Exception {
        assertEquals(200, server.post(user, "/api/sessions/" + session + "/submissions",
                "{\"requestId\":\"r1\",\"mode\":\"prompt\",\"text\":\"hi\"}").statusCode());
        String body = "";
        for (int i = 0; i < 500 && !body.contains("COMPLETED"); i++) {
            Thread.sleep(10);
            body = server.get(user, "/api/sessions/" + session + "/runs").body();
        }
        assertTrue(body.contains("COMPLETED"), body);
        int start = body.indexOf(session + "_");
        return body.substring(start, body.indexOf('"', start));
    }

    @Test
    void anotherUserGetsNotFoundForEverything() throws Exception {
        String run = finishedRun("alice", "s1");

        assertEquals(404, server.get("bob", "/api/sessions/s1/runs").statusCode());
        assertEquals(404, server.get("bob", "/api/runs/" + run).statusCode());
        assertEquals(404, server.get("bob", "/api/runs/" + run + "/turns").statusCode());
        assertEquals(404, server.get("bob", "/api/runs/" + run + "/turns/1").statusCode());
        assertEquals(404, server.post("bob", "/api/runs/" + run + "/cancel", "{}").statusCode());
        assertEquals(404, server.post("bob", "/api/sessions/s1/submissions",
                "{\"requestId\":\"x\",\"mode\":\"prompt\",\"text\":\"hi\"}").statusCode());

        assertEquals(200, server.get("alice", "/api/runs/" + run + "/turns/1").statusCode());
    }

    @Test
    void sameRequestIdFromTwoUsersStaysSeparate() throws Exception {
        finishedRun("alice", "s1");
        finishedRun("bob", "s2");
        assertTrue(server.get("bob", "/api/sessions/s2/runs").body().contains("COMPLETED"));
    }

    @Test
    void eventStreamCarriesOnlyTheOwnersEvents() throws Exception {
        HttpResponse<InputStream> stream = server.http.send(server.request("bob", "/api/events").GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        List<String> bobLines = Collections.synchronizedList(new ArrayList<>());
        Thread.ofVirtual().start(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(stream.body()))) {
                in.lines().forEach(bobLines::add);
            } catch (Exception closed) {
                // 测试结束时连接断开。
            }
        });

        finishedRun("alice", "s1");
        Thread.sleep(300);

        String seen = String.join("\n", bobLines);
        assertFalse(seen.contains(SECRET), seen);
        assertFalse(seen.contains("\"session\":\"s1\""), seen);

        // 确认这个连接确实活着：bob 自己的事件能收到。
        finishedRun("bob", "s2");
        for (int i = 0; i < 300 && !String.join("\n", bobLines).contains("\"session\":\"s2\""); i++) {
            Thread.sleep(10);
        }
        assertTrue(String.join("\n", bobLines).contains("\"session\":\"s2\""));
        stream.body().close();
    }

    @Test
    void replayCatchUpDoesNotLeakAnotherUsersEvents() throws Exception {
        finishedRun("alice", "s1");
        HttpResponse<InputStream> stream = server.http.send(server.request("bob", "/api/events")
                .header("Last-Event-ID", "-1.0").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        List<String> lines = Collections.synchronizedList(new ArrayList<>());
        Thread.ofVirtual().start(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(stream.body()))) {
                in.lines().forEach(lines::add);
            } catch (Exception closed) {
                // 测试结束时连接断开。
            }
        });
        Thread.sleep(300);
        assertFalse(String.join("\n", lines).contains("\"session\":\"s1\""));
        stream.body().close();
    }
}
