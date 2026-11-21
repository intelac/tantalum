package tantalum.shell.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.ModelOutcome;
import tantalum.value.json.Json;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolResultBlock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** live-monitor 需求 1、2：一次带本机工具的提问，每个阶段都推给本人，别人收不到；断开后不再排队。 */
class MonitorApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path temp;

    private TestServer server;

    private final ModelClient model = (prompt, chunks) -> {
        ContentBlock last = prompt.messages().getLast().content().getFirst();
        if (last instanceof ToolResultBlock result) {
            return new ModelOutcome.Replied(List.of(new TextBlock("It says: " + result.text())), StopReason.END_TURN, "m", null);
        }
        return new ModelOutcome.Replied(List.of(new ToolCallBlock("c1", "read_file", "{\"path\":\"a.txt\"}",
                new Json.Obj(Map.of("path", new Json.Str("a.txt"))))), StopReason.TOOL_USE, "m", null);
    };

    @BeforeEach
    void start() throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        Files.createDirectory(workspace.resolve("alice"));
        Files.writeString(workspace.resolve("alice/a.txt"), "hello file");
        server = new TestServer(temp.resolve("data"), workspace, model);
        server.connectComputer("alice", workspace.resolve("alice"), false);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    /** 读 SSE：每条 monitor 消息的数据放进 {@code into}；连上（第一行注释）时放行 {@code open}。 */
    private void listen(String user, List<JsonNode> into, CountDownLatch open) {
        server.http.sendAsync(server.request(user, "/api/monitor/stream").GET().build(), HttpResponse.BodyHandlers.ofLines())
                .thenAccept(response -> {
                    try (Stream<String> lines = response.body()) {
                        lines.forEach(line -> {
                            if (line.startsWith(":")) {
                                open.countDown();
                            } else if (line.startsWith("data:")) {
                                into.add(JSON.readTree(line.substring("data:".length())));
                            }
                        });
                    }
                });
    }

    private static List<String> stages(List<JsonNode> messages) {
        return messages.stream().map(message -> message.get("stage").asString()).toList();
    }

    @Test
    void aPromptWithALocalToolPassesEveryStageAndOnlyItsOwnerSeesIt() throws Exception {
        List<JsonNode> alice = new CopyOnWriteArrayList<>();
        List<JsonNode> bob = new CopyOnWriteArrayList<>();
        CountDownLatch aliceOpen = new CountDownLatch(1);
        CountDownLatch bobOpen = new CountDownLatch(1);
        listen("alice", alice, aliceOpen);
        listen("bob", bob, bobOpen);
        assertTrue(aliceOpen.await(10, TimeUnit.SECONDS));
        assertTrue(bobOpen.await(10, TimeUnit.SECONDS));

        server.post("alice", "/api/sessions/s1/submissions", "{\"requestId\":\"r1\",\"delivery\":\"queue\",\"text\":\"read a.txt\"}");
        for (int i = 0; i < 500 && !(stages(alice).contains("REMOTE_DONE") && endsWithAnswer(alice)); i++) {
            Thread.sleep(10);
        }
        List<String> seen = stages(alice);
        for (String stage : List.of("INTAKE", "INPUT", "LANE", "KERNEL", "JOURNAL", "PUSH", "OUTPUT", "HANDLER_START", "HANDLER_END",
                "REMOTE_SEND", "REMOTE_DONE")) {
            assertTrue(seen.contains(stage), stage + " missing from " + seen);
        }
        assertEquals(Set.of("s1"), Set.copyOf(alice.stream().map(message -> message.get("session").asString()).toList()));
        // 新会话：建会话的事件和提问挨着进来，各自走入口 → 输入环 → 取号 → 内核
        for (String event : List.of("SessionCreated", "UserPrompt")) {
            List<String> path = alice.stream().filter(message -> event.equals(message.path("name").asString()))
                    .map(message -> message.get("stage").asString()).toList();
            assertEquals(List.of("INTAKE", "INPUT", "LANE", "KERNEL"), path.subList(0, 4), event + ": " + path);
        }
        JsonNode lane = alice.stream().filter(message -> message.get("stage").asString().equals("LANE")).findFirst().orElseThrow();
        assertTrue(lane.get("detail").asString().startsWith("seq "));
        List<String> handlers = new ArrayList<>();
        alice.stream().filter(message -> message.get("stage").asString().equals("HANDLER_START"))
                .forEach(message -> handlers.add(message.get("name").asString()));
        assertTrue(handlers.containsAll(List.of("AssembleContext", "CallModel", "read_file")), handlers.toString());
        JsonNode remote = alice.stream().filter(message -> message.get("stage").asString().equals("REMOTE_DONE")).findFirst().orElseThrow();
        assertEquals("read_file", remote.get("name").asString());
        assertTrue(remote.get("detail").asString().endsWith(" ms"));
        JsonNode kernel = alice.stream().filter(message -> message.get("stage").asString().equals("KERNEL")).findFirst().orElseThrow();
        assertTrue(kernel.get("detail").asString().contains("µs"), kernel.toString());
        assertEquals(List.of(), bob, "bob sees none of alice's events");
    }

    private static boolean endsWithAnswer(List<JsonNode> messages) {
        return messages.stream().filter(message -> message.get("stage").asString().equals("HANDLER_END")).count() >= 4;
    }

    @Test
    void theStreamNeedsALogin() throws Exception {
        assertEquals(401, server.get(null, "/api/monitor/stream").statusCode());
    }
}
