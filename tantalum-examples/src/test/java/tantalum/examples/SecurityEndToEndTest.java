package tantalum.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.model.ModelOutcome;
import tantalum.shell.web.TestServer;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;

/**
 * 任务 41（非功能需求 Security）：会话 ID 和运行 ID 来自客户端，经 HTTP 走一遍，不能让文件落到数据目录之外，
 * 也不能读到别人的会话；数据目录里的文件只有本人可读写。
 */
class SecurityEndToEndTest {

    private static final List<String> EVIL = List.of(
            "..", "../escape", "../../escape", "a/../../escape", "..%2Fescape", "%2e%2e%2fescape", "a\\..\\escape",
            "/etc/passwd", "escape\u0000", "x".repeat(300), "s p a c e", "ünïcode", "bob-session");

    @TempDir
    Path temp;

    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static List<Path> everything(Path root) throws Exception {
        try (Stream<Path> files = Files.walk(root)) {
            return files.toList();
        }
    }

    @Test
    void hostileIdsStayInsideTheDataDirectoryAndOtherUsersStayInvisible() throws Exception {
        Path data = temp.resolve("data");
        Path workspace = Files.createDirectories(temp.resolve("workspace"));
        try (TestServer server = new TestServer(data, workspace, (prompt, chunks) ->
                new ModelOutcome.Replied(List.of(new TextBlock("ok")), StopReason.END_TURN, "m", null))) {
            HttpResponse<String> bobs = server.post("bob", "/api/sessions/bob-session/submissions",
                    "{\"requestId\":\"b1\",\"mode\":\"prompt\",\"text\":\"bob's secret\"}");
            assertEquals(200, bobs.statusCode(), bobs.body());
            Thread.sleep(500);
            List<String> statuses = new ArrayList<>();
            for (String evil : EVIL) {
                String id = encode(evil);
                HttpResponse<String> submit = server.post("alice", "/api/sessions/" + id + "/submissions",
                        "{\"requestId\":\"r-" + statuses.size() + "\",\"mode\":\"prompt\",\"text\":\"hi\"}");
                HttpResponse<String> messages = server.get("alice", "/api/sessions/" + id + "/messages");
                HttpResponse<String> runs = server.get("alice", "/api/sessions/" + id + "/runs");
                HttpResponse<String> run = server.get("alice", "/api/runs/" + id + "_1");
                HttpResponse<String> turns = server.get("alice", "/api/runs/" + id + "_1/turns");
                HttpResponse<String> trace = server.get("alice", "/api/runs/" + id + "_1/trace");
                HttpResponse<String> cancel = server.http.send(server.request("alice", "/api/runs/" + id + "_1/cancel")
                        .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
                for (HttpResponse<String> response : List.of(submit, messages, runs, run, turns, trace, cancel)) {
                    statuses.add(evil + " " + response.request().method() + " " + response.statusCode());
                    assertTrue(response.statusCode() == 400 || response.statusCode() == 404,
                            evil + ": " + response.request().uri() + " answered " + response.statusCode() + " " + response.body());
                    assertTrue(!response.body().contains("bob's secret"), "alice read bob's session through " + response.request().uri());
                }
            }
            Thread.sleep(300);
        }
        for (Path path : everything(temp)) {
            assertTrue(path.startsWith(data) || path.startsWith(temp.resolve("workspace")) || path.equals(temp),
                    "a file landed outside the data directory: " + path);
        }
    }

    @Test
    void everythingInTheDataDirectoryIsPrivate() throws Exception {
        Path data = temp.resolve("data");
        Path workspace = Files.createDirectories(temp.resolve("workspace"));
        try (TestServer server = new TestServer(data, workspace, (prompt, chunks) ->
                new ModelOutcome.Replied(List.of(new TextBlock("ok")), StopReason.END_TURN, "m", null))) {
            assertEquals(200, server.post("alice", "/api/sessions/s1/submissions",
                    "{\"requestId\":\"r1\",\"mode\":\"prompt\",\"text\":\"hi\"}").statusCode());
            Thread.sleep(500);
            List<String> exposed = exposed(data);
            assertTrue(exposed.isEmpty(), "readable by others while running: " + exposed);
        }
        List<String> exposed = exposed(data);
        assertTrue(exposed.isEmpty(), "readable by others after shutdown: " + exposed);
    }

    private static List<String> exposed(Path data) throws Exception {
        List<String> exposed = new ArrayList<>();
        for (Path path : everything(data)) {
            if (path.equals(data)) {
                continue;
            }
            String mode = PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
            boolean ok = Files.isDirectory(path) ? mode.equals("rwx------") : Set.of("rw-------", "r--------").contains(mode);
            if (!ok) {
                exposed.add(data.relativize(path) + " " + mode);
            }
        }
        return exposed;
    }
}
