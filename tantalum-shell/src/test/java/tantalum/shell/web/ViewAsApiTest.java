package tantalum.shell.web;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import tantalum.shell.auth.ViewAs;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.ModelOutcome;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * qagent-align 需求 6：管理员只读查看别人的会话。请求带 {@code viewAs=用户名}，服务器把这一次请求的身份换成被看的人——
 * 只对管理员、只对 GET、只对白名单里的读接口；别的一律拒绝。查看不改被看的人的任何东西。
 */
class ViewAsApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path temp;

    private TestServer server;
    private final List<String> log = new CopyOnWriteArrayList<>();
    private ListAppender<ILoggingEvent> appender;

    private final ModelClient model = (prompt, chunks) -> {
        chunks.onText("Hel");
        return new ModelOutcome.Replied(List.of(new TextBlock("Hello from alice's session")), StopReason.END_TURN, "m", null);
    };

    @BeforeEach
    void start() throws Exception {
        Files.createDirectories(temp.resolve("ws"));
        server = open();
        appender = new ListAppender<>() {
            @Override protected void append(ILoggingEvent event) {
                log.add(event.getFormattedMessage());
            }
        };
        appender.start();
        ((Logger) LoggerFactory.getLogger(ViewAs.class)).addAppender(appender);
    }

    private TestServer open(String... extra) throws Exception {
        String[] args = java.util.stream.Stream.concat(java.util.stream.Stream.of("--tantalum.sse-heartbeat=200ms", "--tantalum.sse-poll-hold=200ms"),
                java.util.stream.Stream.of(extra)).toArray(String[]::new);
        TestServer opened = new TestServer(temp.resolve("data"), temp.resolve("ws"), model, args);
        if (opened.users().status("root").isEmpty()) {
            opened.users().add("root", TestServer.PASSWORD, true);
        }
        return opened;
    }

    @AfterEach
    void stop() {
        ((Logger) LoggerFactory.getLogger(ViewAs.class)).detachAppender(appender);
        server.close();
    }

    private HttpResponse<String> send(String user, String method, String path, String body) throws Exception {
        HttpRequest.Builder request = server.request(user, path).header("content-type", "application/json");
        return server.http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private void ask(String user, String session, String request) throws Exception {
        assertEquals(200, send(user, "POST", "/api/sessions/" + session + "/submissions",
                "{\"requestId\":\"" + request + "\",\"delivery\":\"queue\",\"text\":\"hi from " + user + "\"}").statusCode());
        for (int i = 0; i < 1000; i++) {
            JsonNode runs = JSON.readTree(send(user, "GET", "/api/sessions/" + session + "/runs", null).body()).get("runs");
            if (runs != null && runs.size() > 0 && runs.get(runs.size() - 1).has("endReason")) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("the run did not end");
    }

    private String runOf(String user, String session) throws Exception {
        return JSON.readTree(send(user, "GET", "/api/sessions/" + session + "/runs", null).body()).get("runs").get(0).get("run").asString();
    }

    /** 数据目录里每个文件的内容（索引和用户库是 SQLite，查看只读它们；登录表的 last_seen 是管理员自己的行，单独看）。 */
    private Map<String, byte[]> files(Path dir) throws Exception {
        Map<String, byte[]> all = new TreeMap<>();
        try (var walk = Files.walk(dir)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                all.put(dir.relativize(file).toString(), Files.readAllBytes(file));
            }
        }
        return all;
    }

    @Test
    void anAdministratorReadsAnotherPersonsSessionsThroughTheListedEndpoints() throws Exception {
        ask("alice", "s1", "r1");
        ask("root", "mine", "r2");
        String run = runOf("alice", "s1");

        // 不带参数：管理员看到的是自己的，读不到 alice 的
        assertFalse(send("root", "GET", "/api/sessions", null).body().contains("\"id\":\"s1\""));
        assertEquals(404, send("root", "GET", "/api/sessions/s1/messages", null).statusCode());

        JsonNode sessions = JSON.readTree(send("root", "GET", "/api/sessions?viewAs=alice", null).body()).get("sessions");
        assertEquals(1, sessions.size(), sessions.toString());
        assertEquals("s1", sessions.get(0).get("id").asString());
        assertTrue(send("root", "GET", "/api/sessions/s1/messages?viewAs=alice", null).body().contains("Hello from alice's session"));
        for (String path : List.of("/api/sessions/s1/runs", "/api/runs/" + run, "/api/sessions/s1/trace", "/api/runs/" + run + "/turns",
                "/api/runs/" + run + "/turns/1", "/api/sessions/s1/context", "/api/sessions/s1/questions", "/api/projects",
                "/api/events/poll")) {
            HttpResponse<String> read = send("root", "GET", path + "?viewAs=alice", null);
            assertEquals(200, read.statusCode(), path + ": " + read.body());
        }
        // 自己的会话在查看别人时读不到：这时“当前用户”是被看的人
        assertEquals(404, send("root", "GET", "/api/sessions/mine/messages?viewAs=alice", null).statusCode());
        assertEquals(404, send("root", "GET", "/api/sessions?viewAs=nobody", null).statusCode(), "no such user");
    }

    @Test
    void nothingCanBeChangedAndNothingElseCanBeRead() throws Exception {
        ask("alice", "s1", "r1");
        String run = runOf("alice", "s1");
        String[][] writes = {
                {"POST", "/api/sessions/s1/submissions", "{\"requestId\":\"x\",\"delivery\":\"queue\",\"text\":\"as alice\"}"},
                {"POST", "/api/sessions/s1/compact", "{}"},
                {"POST", "/api/runs/" + run + "/cancel", "{}"},
                {"PATCH", "/api/sessions/s1", "{\"title\":\"renamed\"}"},
                {"PUT", "/api/sessions/s1/archived", "{\"archived\":true}"},
                {"POST", "/api/sessions/s1/queue/q1", "{\"action\":\"remove\"}"},
                {"POST", "/api/sessions/s1/questions/q1", "{\"choice\":\"yes\"}"},
                {"POST", "/api/sessions/s1/attachments", "x"},
                {"POST", "/api/projects", "{\"path\":\"/tmp\"}"},
                {"PATCH", "/api/projects/p1", "{\"title\":\"t\"}"},
                {"DELETE", "/api/projects/p1", null},
                {"POST", "/api/plugin-api/memory/entries", "{\"name\":\"n\",\"content\":\"c\"}"},
                {"DELETE", "/api/plugin-api/memory/entries/n", null},
                {"PUT", "/api/plugins/tool/ask_user", "{\"enabled\":false}"},
                {"DELETE", "/api/plugins/tool/ask_user", null},
                {"PUT", "/api/plugins/tool/ask_user/params", "{\"values\":{}}"},
                {"POST", "/api/local/tokens", "{\"validity\":\"1h\"}"},
                {"DELETE", "/api/local/tokens", null},
                {"PUT", "/api/local/skills", "{\"dir\":\"~/x\"}"},
                {"PUT", "/api/settings/model", "{\"route\":\"claude\",\"model\":\"m\"}"},
                {"PUT", "/api/settings/language", "{\"language\":\"en\"}"},
                {"PUT", "/api/settings/compaction", "{\"percent\":50}"},
                {"PUT", "/api/auth/password", "{\"current\":\"a\",\"password\":\"b\"}"},
                {"POST", "/api/auth/logout", ""},
                {"POST", "/api/users", "{\"name\":\"eve\",\"password\":\"long-enough-password\",\"admin\":true}"},
                {"PUT", "/api/users/alice", "{\"disabled\":true}"},
                {"PUT", "/api/users/alice/password", "{\"password\":\"long-enough-password\"}"},
                {"PUT", "/api/admin/plugins/tool/ask_user", "{\"locked\":true}"},
                {"PUT", "/api/admin/users/alice/params/plugin/x", "{\"values\":{}}"},
        };
        for (String[] write : writes) {
            HttpResponse<String> refused = send("root", write[0], write[1] + "?viewAs=alice", write[2]);
            assertEquals(403, refused.statusCode(), write[0] + " " + write[1] + ": " + refused.body());
            assertTrue(refused.body().contains("\"code\":\"view/read-only\""), write[0] + " " + write[1] + ": " + refused.body());
        }
        // 读，但不在允许的范围里：记忆、插件和参数、本机工作区和令牌、技能、设置、今日用量、账号、管理接口
        for (String path : List.of("/api/plugin-api/memory/entries", "/api/plugin-api/memory/entries/n", "/api/plugins", "/api/local", "/api/local/skills", "/api/local/wiki",
                "/api/skills", "/api/settings", "/api/usage/today", "/api/models", "/api/config", "/api/auth/me", "/api/projects/browse",
                "/api/users", "/api/admin/plugins", "/api/admin/users/alice/params")) {
            HttpResponse<String> refused = send("root", "GET", path + "?viewAs=alice", null);
            assertEquals(403, refused.statusCode(), path + ": " + refused.body());
            assertTrue(refused.body().contains("\"code\":\"view/read-only\""), path + ": " + refused.body());
        }
        // 什么都没变
        JsonNode session = JSON.readTree(send("alice", "GET", "/api/sessions", null).body()).get("sessions").get(0);
        assertFalse(session.has("archived") && session.get("archived").asBoolean());
        assertEquals(1, JSON.readTree(send("alice", "GET", "/api/sessions/s1/runs", null).body()).get("runs").size());
        assertEquals(200, send("alice", "GET", "/api/auth/me", null).statusCode(), "alice is still signed in and enabled");
        assertEquals(200, send("root", "GET", "/api/auth/me", null).statusCode(), "the administrator was not signed out");
    }

    @Test
    void onlyAnAdministratorMayViewAndTheFeatureCanBeTurnedOff() throws Exception {
        ask("alice", "s1", "r1");
        ask("bob", "s2", "r2");
        HttpResponse<String> refused = send("bob", "GET", "/api/sessions?viewAs=alice", null);
        assertEquals(403, refused.statusCode());
        assertTrue(refused.body().contains("\"code\":\"view/forbidden\""), refused.body());
        assertEquals(403, send("bob", "GET", "/api/sessions/s1/messages?viewAs=alice", null).statusCode());
        assertEquals(403, send("bob", "POST", "/api/sessions/s1/submissions?viewAs=alice", "{}").statusCode());
        assertEquals(401, server.http.send(server.request(null, "/api/sessions?viewAs=alice").GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode(), "not signed in");
        assertTrue(send("root", "GET", "/api/users", null).body().contains("\"viewAs\":true"));

        server.close();
        server = open("--tantalum.view-as.enabled=false");
        HttpResponse<String> off = send("root", "GET", "/api/sessions?viewAs=alice", null);
        assertEquals(403, off.statusCode());
        assertTrue(off.body().contains("\"code\":\"view/forbidden\""), off.body());
        assertTrue(send("root", "GET", "/api/users", null).body().contains("\"viewAs\":false"));
        assertEquals(200, send("root", "GET", "/api/sessions", null).statusCode(), "without the parameter everything is as before");
    }

    @Test
    void eachStartOfViewingIsLoggedOnceWithoutContent() throws Exception {
        ask("alice", "s1", "r1");
        ask("bob", "s2", "r2");
        log.clear();
        send("root", "GET", "/api/sessions?viewAs=alice", null);
        send("root", "GET", "/api/sessions/s1/messages?viewAs=alice", null);
        send("root", "GET", "/api/sessions/s1/trace?viewAs=alice", null);
        assertEquals(List.of("admin root is viewing alice's sessions (read-only)"), List.copyOf(log));
        send("root", "GET", "/api/sessions?viewAs=bob", null);
        assertEquals(2, log.size(), "another person is another line: " + log);
        // 被拒绝的不算开始查看
        send("bob", "GET", "/api/sessions?viewAs=alice", null);
        send("root", "POST", "/api/sessions/s1/compact?viewAs=alice", "{}");
        assertEquals(2, log.size(), log.toString());
        assertTrue(log.stream().noneMatch(line -> line.contains("Hello") || line.contains("s1")), "no content, no paths: " + log);
    }

    @Test
    void viewingChangesNothingOfThePersonViewed() throws Exception {
        ask("alice", "s1", "r1");
        String run = runOf("alice", "s1");
        Thread.sleep(300);
        Map<String, byte[]> before = files(temp.resolve("data/sessions"));
        List<String> workspaceBefore = listed(temp.resolve("ws"));
        byte[] attachments = Files.exists(temp.resolve("data/attachments")) ? new byte[] {1} : new byte[] {0};
        for (String path : List.of("/api/sessions", "/api/sessions/s1/messages", "/api/sessions/s1/runs", "/api/runs/" + run,
                "/api/sessions/s1/trace", "/api/runs/" + run + "/turns", "/api/sessions/s1/context", "/api/sessions/s1/questions",
                "/api/projects", "/api/events/poll", "/api/plugin-api/memory/entries", "/api/local")) {
            send("root", "GET", path + "?viewAs=alice", null);
        }
        Map<String, byte[]> after = files(temp.resolve("data/sessions"));
        assertEquals(before.keySet(), after.keySet());
        for (String file : before.keySet()) {
            assertArrayEquals(before.get(file), after.get(file), file);
        }
        // 读记忆会给人建工作目录：只读查看不许读，所以目录没有多出来
        assertEquals(workspaceBefore, listed(temp.resolve("ws")));
        assertArrayEquals(attachments, Files.exists(temp.resolve("data/attachments")) ? new byte[] {1} : new byte[] {0});
        // alice 自己照常能用
        ask("alice", "s1", "r2");
    }

    private static List<String> listed(Path dir) throws Exception {
        try (var walk = Files.walk(dir)) {
            return walk.map(path -> dir.relativize(path).toString()).sorted().toList();
        }
    }

    /** 一条长连接：在后台读行。 */
    private final class Stream {
        final List<String> lines = new CopyOnWriteArrayList<>();
        final CountDownLatch ended = new CountDownLatch(1);

        Stream(String user, String path) throws Exception {
            HttpResponse<InputStream> response = server.http.send(server.request(user, path).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, response.statusCode());
            Thread.ofVirtual().start(() -> {
                try (BufferedReader in = new BufferedReader(new InputStreamReader(response.body()))) {
                    in.lines().forEach(lines::add);
                } catch (Exception closed) {
                    // 连接断开
                } finally {
                    ended.countDown();
                }
            });
        }

        boolean saw(String text) throws InterruptedException {
            for (int i = 0; i < 300 && lines.stream().noneMatch(line -> line.contains(text)); i++) {
                Thread.sleep(10);
            }
            return lines.stream().anyMatch(line -> line.contains(text));
        }
    }

    @Test
    void theLiveStreamCarriesWhatThePersonViewedSeesAndEndsWhenTheViewerIsNoLongerAnAdministrator() throws Exception {
        ask("alice", "s1", "r1");
        Stream viewing = new Stream("root", "/api/events?viewAs=alice");
        Stream own = new Stream("root", "/api/events");
        Thread.sleep(300);
        ask("alice", "s1", "r2");
        assertTrue(viewing.saw("\"session\":\"s1\""), "live events of the person viewed: " + viewing.lines);
        assertTrue(viewing.saw("event:chunk"), "chunks too, as that person sees them");
        Thread.sleep(200);
        assertFalse(own.lines.stream().anyMatch(line -> line.contains("\"session\":\"s1\"")), "the administrator's own stream stays their own");
        // 管理员自己的事不进查看别人的那条
        ask("root", "mine", "r3");
        Thread.sleep(200);
        assertFalse(viewing.lines.stream().anyMatch(line -> line.contains("\"session\":\"mine\"")));

        // 不再是管理员：一个心跳内断开查看的那条，自己的那条还在
        server.users().add("second", TestServer.PASSWORD, true);
        server.users().setAdmin("root", false);
        assertTrue(viewing.ended.await(3, TimeUnit.SECONDS), "the viewing stream must end");
        assertFalse(own.ended.await(400, TimeUnit.MILLISECONDS), "the own stream goes on");
        assertEquals(403, send("root", "GET", "/api/sessions?viewAs=alice", null).statusCode());
    }

    /** 轮询的后备通道也一样：订阅的是被看的人，登录是管理员自己的。 */
    @Test
    void thePollingFallbackWorksWhileViewing() throws Exception {
        ask("alice", "s1", "r1");
        String token = send("root", "GET", "/api/events/poll?viewAs=alice", null).headers().firstValue(EventPollController.STREAM).orElseThrow();
        ask("alice", "s1", "r2");
        List<String> bodies = new ArrayList<>();
        for (int i = 0; i < 30 && bodies.stream().noneMatch(body -> body.contains("\"session\":\"s1\"")); i++) {
            HttpResponse<String> next = send("root", "GET", "/api/events/poll?stream=" + token + "&viewAs=alice", null);
            assertEquals(200, next.statusCode(), next.body());
            bodies.add(next.body());
        }
        assertTrue(bodies.stream().anyMatch(body -> body.contains("\"session\":\"s1\"")), bodies.toString());
        // 同一个编号不带查看参数就是另一个人的了：不认
        assertEquals(410, send("root", "GET", "/api/events/poll?stream=" + token, null).statusCode());
    }

    /**
     * memory-final-plan 第 13 节（Yi 2026-11-15 定）：管理员只读查看时，记忆（个人插件）的内容在任何接口里都看不到；
     * 每次查看都记下，被看的人自己查得到；管理员只能把记忆锁成关，不能锁成开。
     */
    @Test
    void anAdministratorViewingSeesNoMemoryAndEveryViewIsRecorded() throws Exception {
        assertEquals(201, send("alice", "POST", "/api/plugin-api/memory/entries",
                "{\"name\":\"secret-plan\",\"type\":\"project\",\"description\":\"zebra-quartz plan\",\"content\":\"zebra-quartz details\"}")
                .statusCode());
        ask("alice", "s1", "r1");
        String run = runOf("alice", "s1");
        for (String path : List.of("/api/sessions/s1/context", "/api/sessions/s1/trace", "/api/sessions/s1/messages",
                "/api/sessions/s1/runs/" + run + "/tree", "/api/runs/" + run + "/turns/1")) {
            HttpResponse<String> viewed = send("root", "GET", path + "?viewAs=alice", null);
            assertEquals(200, viewed.statusCode(), path + ": " + viewed.body());
            assertFalse(viewed.body().contains("zebra-quartz"), path + " shows memory: " + viewed.body());
        }
        JsonNode trace = JSON.readTree(send("alice", "GET", "/api/sessions/s1/trace", null).body());
        int calls = 0;
        for (JsonNode record : trace.get("records")) {
            if (!"request".equals(record.path("kind").asString())) {
                continue;
            }
            for (JsonNode attempt : record.path("attempts")) {
                String command = attempt.path("call").asString();
                // 本人看得到：一次模型调用的完整输入里有记忆索引；管理员只读查看时被遮掉
                assertTrue(send("alice", "GET", "/api/sessions/s1/trace/calls/" + command, null).body().contains("zebra-quartz"));
                String viewed = send("root", "GET", "/api/sessions/s1/trace/calls/" + command + "?viewAs=alice", null).body();
                assertFalse(viewed.contains("zebra-quartz"), "the full input of a model call hides memory: " + viewed);
                assertTrue(viewed.contains("[hidden]"), viewed);
                calls++;
            }
        }
        assertTrue(calls > 0, trace.toString());
        assertEquals(403, send("root", "GET", "/api/plugin-api/memory/notes?viewAs=alice", null).statusCode(), "daily notes are memory too");
        assertEquals(403, send("root", "GET", "/api/plugin-api/memory/entries?viewAs=alice", null).statusCode());

        JsonNode views = JSON.readTree(send("alice", "GET", "/api/settings/views", null).body()).get("views");
        assertTrue(views.size() >= 1, views.toString());
        assertTrue(java.util.stream.StreamSupport.stream(views.spliterator(), false)
                .anyMatch(view -> "root".equals(view.get("viewer").asString()) && "s1".equals(view.path("session").asString())), views.toString());
        assertEquals(0, JSON.readTree(send("root", "GET", "/api/settings/views", null).body()).get("views").size(), "nobody viewed root");
        assertEquals(403, send("root", "GET", "/api/settings/views?viewAs=alice", null).statusCode(), "an admin cannot read the log as alice");

        HttpResponse<String> lockOn = send("root", "PUT", "/api/admin/plugins/plugin/memory", "{\"enabled\":true,\"locked\":true,\"reason\":\"x\"}");
        assertEquals(400, lockOn.statusCode(), lockOn.body());
        assertTrue(lockOn.body().contains("\"code\":\"plugin/personal\""), lockOn.body());
        assertEquals(200, send("root", "PUT", "/api/admin/plugins/plugin/memory", "{\"enabled\":false,\"locked\":true,\"reason\":\"x\"}")
                .statusCode(), "locking it off is allowed");
    }
}
