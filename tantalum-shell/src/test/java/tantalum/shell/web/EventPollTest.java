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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.ModelOutcome;
import tantalum.shell.sse.SseMessage;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;

/** 长连接被中间的代理憋住时的后备：页面一次次来取，订阅留在服务器上，两次请求之间的消息不丢。 */
class EventPollTest {

    @TempDir
    Path temp;

    /** 回答分两片流出来：分片没有 id，补读不回来，只有订阅一直留着才收得到。 */
    private final ModelClient model = (prompt, chunks) -> {
        chunks.onText("Hel");
        chunks.onText("lo");
        return new ModelOutcome.Replied(List.of(new TextBlock("Hello")), StopReason.END_TURN, "m", null);
    };

    private TestServer server(String... extra) throws Exception {
        Files.createDirectories(temp.resolve("workspace"));
        return new TestServer(temp.resolve("data"), temp.resolve("workspace"), model, extra);
    }

    private static String token(HttpResponse<String> response) {
        return response.headers().firstValue(EventPollController.STREAM).orElseThrow();
    }

    private static void submit(TestServer server, String user, String session, String request) throws Exception {
        HttpResponse<String> submitted = server.post(user, "/api/sessions/" + session + "/submissions",
                "{\"requestId\":\"" + request + "\",\"delivery\":\"queue\",\"text\":\"hi\"}");
        assertEquals(200, submitted.statusCode(), submitted.body());
    }

    /** 接着取，直到出现 {@code wanted} 或次数用完；返回取到的全部文本。 */
    private static String pollUntil(TestServer server, String user, String token, String wanted) throws Exception {
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < 50 && all.indexOf(wanted) < 0; i++) {
            HttpResponse<String> next = server.get(user, "/api/events/poll?stream=" + token);
            assertEquals(200, next.statusCode());
            assertEquals(token, token(next), "the same stream keeps its number");
            all.append(next.body());
        }
        return all.toString();
    }

    @Test
    void theFirstRequestOpensAStreamAndLaterOnesCollectWhatArrivedInBetween() throws Exception {
        try (TestServer server = server("--tantalum.sse-poll-hold=300ms")) {
            HttpResponse<String> opened = server.get("alice", "/api/events/poll");
            assertEquals(200, opened.statusCode());
            assertEquals("", opened.body(), "nothing has happened yet");
            assertTrue(opened.headers().firstValue("content-type").orElseThrow().startsWith("text/plain"));
            String token = token(opened);

            // 提问发生在两次请求之间：这时没有任何请求挂着
            submit(server, "alice", "s1", "r1");
            String all = pollUntil(server, "alice", token, "\"endReason\":\"COMPLETED\"");

            assertTrue(all.contains("event:chunk\n"), "chunks have no id and cannot be replayed; they must still arrive: " + all);
            assertTrue(all.contains("\"Hel\"") && all.contains("\"lo\""), all);
            assertTrue(all.contains("event:item\n") && all.contains("\nid:") || all.startsWith("id:"), all);
            assertTrue(all.contains("\"endReason\":\"COMPLETED\""), all);
        }
    }

    @Test
    void aWaitingRequestReturnsAsSoonAsSomethingHappens() throws Exception {
        try (TestServer server = server("--tantalum.sse-poll-hold=20s")) {
            String token = token(server.get("alice", "/api/events/poll"));
            CompletableFuture<HttpResponse<String>> waiting = CompletableFuture.supplyAsync(() -> {
                try {
                    return server.get("alice", "/api/events/poll?stream=" + token);
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            });
            Thread.sleep(300);
            assertFalse(waiting.isDone(), "nothing to deliver yet: the request stays open");
            submit(server, "alice", "s1", "r1");
            HttpResponse<String> delivered = waiting.get(5, TimeUnit.SECONDS);
            assertEquals(200, delivered.statusCode());
            assertFalse(delivered.body().isEmpty());
        }
    }

    @Test
    void aQuietStreamAnswersEmptyAfterTheHold() throws Exception {
        try (TestServer server = server("--tantalum.sse-poll-hold=200ms")) {
            String token = token(server.get("alice", "/api/events/poll"));
            long started = System.nanoTime();
            HttpResponse<String> quiet = server.get("alice", "/api/events/poll?stream=" + token);
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertEquals(200, quiet.statusCode());
            assertEquals("", quiet.body());
            assertTrue(millis >= 150 && millis < 3_000, "held for about the configured time, was " + millis + " ms");
        }
    }

    @Test
    void aNewStreamCatchesUpFromTheLastEventId() throws Exception {
        try (TestServer server = server("--tantalum.sse-poll-hold=300ms")) {
            String token = token(server.get("alice", "/api/events/poll"));
            submit(server, "alice", "s1", "r1");
            String first = pollUntil(server, "alice", token, "\"endReason\":\"COMPLETED\"");
            String firstId = first.lines().filter(line -> line.startsWith("id:")).findFirst().orElseThrow().substring(3);

            HttpResponse<String> again = server.http.send(server.request("alice", "/api/events/poll")
                    .header("Last-Event-ID", firstId).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, again.statusCode());
            assertTrue(again.body().contains("\"endReason\":\"COMPLETED\""), "what came after that id is replayed: " + again.body());
            assertFalse(again.body().contains("id:" + firstId + "\n"), "the id itself is not repeated");
        }
    }

    @Test
    void aStreamBelongsToTheLoginThatOpenedIt() throws Exception {
        try (TestServer server = server("--tantalum.sse-poll-hold=200ms")) {
            String token = token(server.get("alice", "/api/events/poll"));
            assertEquals(410, server.get("bob", "/api/events/poll?stream=" + token).statusCode(), "another user");
            assertEquals(410, server.get("alice", "/api/events/poll?stream=no-such-stream").statusCode());
            assertEquals(401, server.http.send(server.request(null, "/api/events/poll").GET().build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode(), "not logged in");

            // 另一个用户的事不会进来
            submit(server, "bob", "s9", "r9");
            Thread.sleep(300);
            assertEquals("", server.get("alice", "/api/events/poll?stream=" + token).body());

            // 退出登录：这条订阅关掉，旧编号不再有效
            assertEquals(200, server.post("alice", "/api/auth/logout", "").statusCode());
            assertEquals(401, server.get("alice", "/api/events/poll?stream=" + token).statusCode());
        }
    }

    @Test
    void aStreamNobodyCollectsIsClosed() throws Exception {
        try (TestServer server = server("--tantalum.sse-poll-hold=100ms", "--tantalum.sse-poll-idle=200ms")) {
            String stale = token(server.get("alice", "/api/events/poll"));
            Thread.sleep(400);
            server.get("alice", "/api/events/poll");
            assertEquals(410, server.get("alice", "/api/events/poll?stream=" + stale).statusCode());
        }
    }

    @Test
    void messagesAreWrittenLikeServerSentEvents() {
        assertEquals("id:3.1\nevent:item\ndata:{\"a\":1}\n\nevent:chunk\ndata:two\ndata:lines\n\n",
                EventPollController.text(List.of(new SseMessage("3.1", "item", "{\"a\":1}"), new SseMessage(null, "chunk", "two\nlines"))));
    }

    /** 长连接一连上就有一行，页面靠它分辨“连上了”和“被代理憋住了”。 */
    @Test
    void theLongLivedStreamSaysHelloAtOnce() throws Exception {
        try (TestServer server = server()) {
            HttpResponse<InputStream> stream = server.http.send(server.request("alice", "/api/events").GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            CompletableFuture<String> first = CompletableFuture.supplyAsync(() -> {
                try {
                    return new BufferedReader(new InputStreamReader(stream.body())).readLine();
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            });
            assertEquals(":open", first.get(3, TimeUnit.SECONDS));
            stream.body().close();
        }
    }
}
