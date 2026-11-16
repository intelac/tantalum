package tantalum.shell.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.ModelOutcome;
import tantalum.value.json.Json;
import tantalum.value.model.Message;
import tantalum.value.model.Role;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolResultBlock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** memory-final-plan 第 12 节：委派最多两层；每一层的结果交回上一层；子会话在侧栏带着父会话。 */
class DelegateApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path temp;

    private final List<String> inputs = new CopyOnWriteArrayList<>();

    /** 每一轮第一次都想再交给别人；拿到工具结果（回话，或者“自己做”的拒绝）后把它原样交回去。 */
    private final ModelClient model = (prompt, chunks) -> {
        inputs.add(prompt.toString());
        Message last = prompt.messages().getLast();
        String result = last.content().stream().filter(ToolResultBlock.class::isInstance).map(block -> ((ToolResultBlock) block).text())
                .findFirst().orElse(null);
        if (last.role() == Role.USER && result == null) {
            return new ModelOutcome.Replied(List.of(new ToolCallBlock("c-" + inputs.size(), "delegate", "{\"task\":\"go deeper\",\"wait\":true}",
                    new Json.Obj(Map.of("task", new Json.Str("go deeper"), "wait", new Json.Bool(true))))), StopReason.TOOL_USE, "m", null);
        }
        return new ModelOutcome.Replied(List.of(new TextBlock("done: " + (result == null ? "" : result.lines().findFirst().orElse("")))),
                StopReason.END_TURN, "m", null);
    };

    @Test
    void aConversationCanAskAnotherOneButThatOneDoesTheWorkItself() throws Exception {
        try (TestServer server = new TestServer(temp.resolve("data"), Files.createDirectory(temp.resolve("ws")), model)) {
            server.post("alice", "/api/sessions/top/submissions", "{\"requestId\":\"r1\",\"mode\":\"prompt\",\"text\":\"start\"}");
            for (int i = 0; i < 1500; i++) {
                JsonNode runs = JSON.readTree(server.get("alice", "/api/sessions/top/runs").body()).get("runs");
                if (runs != null && runs.size() > 0 && runs.get(runs.size() - 1).has("endReason")) {
                    break;
                }
                Thread.sleep(10);
            }
            assertTrue(inputs.stream().anyMatch(input -> input.contains("do the work here instead of handing")),
                    "the third level is refused: " + inputs.size() + " calls, sessions " + server.get("alice", "/api/sessions").body()
                            + " last input " + (inputs.isEmpty() ? "" : inputs.getLast().substring(Math.max(0, inputs.getLast().length() - 600))));
            JsonNode sessions = JSON.readTree(server.get("alice", "/api/sessions").body()).get("sessions");
            assertEquals(2, sessions.size(), "only one level: " + sessions);
            long children = 0;
            for (JsonNode session : sessions) {
                if (session.has("parent")) {
                    children++;
                }
            }
            assertEquals(1, children, "the list says which conversation opened the other");
            assertTrue(server.get("alice", "/api/sessions/top/messages").body().contains("Answer from conversation s-"));
        }
    }

    /** 不等结果：主窗口交出去就结束这一轮；对方做完，结果作为一条消息回来，把空闲的主窗口叫醒，再跑一轮。全程没有人再说话。 */
    @Test
    void withoutWaitingTheAnswerComesBackLaterAndWakesTheOpener() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        ModelClient window = (prompt, chunks) -> {
            String last = prompt.messages().stream().map(message -> message.content().stream().map(block -> block instanceof TextBlock text ? text.text()
                    : block instanceof ToolResultBlock result ? result.text() : "").reduce("", String::concat))
                    // 上下文有变化时内核在后面追加一条说明，不是谁说的话
                    .filter(text -> !text.startsWith("[Context update")).reduce((a, b) -> b).orElse("");
            seen.add(last);
            if (last.equals("start")) {
                return new ModelOutcome.Replied(List.of(new ToolCallBlock("c1", "delegate", "{\"task\":\"count the files\"}",
                        new Json.Obj(Map.of("task", new Json.Str("count the files"))))), StopReason.TOOL_USE, "m", null);
            }
            String reply = last.startsWith("Opened conversation") ? "handed off" : last.contains("Task:\ncount the files") ? "42 files"
                    : "the other conversation says: " + last.lines().reduce((a, b) -> b).orElse("");
            return new ModelOutcome.Replied(List.of(new TextBlock(reply)), StopReason.END_TURN, "m", null);
        };
        try (TestServer server = new TestServer(temp.resolve("data2"), Files.createDirectory(temp.resolve("ws2")), window)) {
            server.post("alice", "/api/sessions/main-2026-11-16/submissions", "{\"requestId\":\"r1\",\"mode\":\"prompt\",\"text\":\"start\"}");
            String messages = "";
            for (int i = 0; i < 1500 && !messages.contains("the other conversation says: 42 files"); i++) {
                Thread.sleep(10);
                messages = server.get("alice", "/api/sessions/main-2026-11-16/messages").body();
            }
            assertTrue(messages.contains("the other conversation says: 42 files"), "the answer came back and started a second turn: " + seen);
            assertTrue(seen.stream().anyMatch(input -> input.startsWith("Answer from a conversation you handed work to (not from the user).")),
                    "it arrived marked as not from the user");
            JsonNode runs = JSON.readTree(server.get("alice", "/api/sessions/main-2026-11-16/runs").body()).get("runs");
            assertEquals(2, runs.size(), "one turn for the user's prompt, one woken by the answer");
            JsonNode sessions = JSON.readTree(server.get("alice", "/api/sessions").body()).get("sessions");
            assertEquals(2, sessions.size(), sessions.toString());
            String opened = null;
            for (JsonNode session : sessions) {
                if (!session.get("id").asString().equals("main-2026-11-16")) {
                    opened = session.get("id").asString();
                    assertEquals("main-2026-11-16", session.path("parent").asString(), "the list says who opened it");
                    assertEquals("count the files", session.path("title").asString(), "its title is the task, not the preamble");
                }
            }
            // 对话页：两边的这两句话都不是用户说的，带着是哪个会话送来的；文字不带给模型看的开场白
            List<JsonNode> back = users(JSON.readTree(messages));
            assertEquals(2, back.size(), messages);
            assertTrue(back.get(0).path("from").isMissingNode(), "what the user typed carries no source");
            assertEquals("42 files", back.get(1).path("text").asString());
            assertEquals(opened, back.get(1).path("from").path("session").asString());
            assertTrue(back.get(1).path("from").path("reply").asBoolean(), "it is the answer to what was handed off");
            assertTrue(back.get(1).path("from").path("failed").isMissingNode());
            List<JsonNode> there = users(JSON.readTree(server.get("alice", "/api/sessions/" + opened + "/messages").body()));
            assertEquals(1, there.size());
            assertEquals("count the files", there.get(0).path("text").asString(), "only the task, not the preamble");
            assertEquals("main-2026-11-16", there.get(0).path("from").path("session").asString());
            assertEquals(false, there.get(0).path("from").path("reply").asBoolean());
            assertTrue(there.get(0).path("run").asString().startsWith(opened), "it opens the turn that does the work");
        }
    }

    /**
     * 收回：主窗口交出去一件做不完的活，用户说别做了，模型把它收回。那个会话的一轮按被取消结束，照常回一句“没做成”，
     * 把主窗口叫醒；对话页上那句回话标着没做成。
     */
    @Test
    void workHandedOffCanBeTakenBackAndStillAnswers() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> opened = new java.util.concurrent.atomic.AtomicReference<>();
        ModelClient window = (prompt, chunks) -> {
            String last = prompt.messages().stream().map(message -> message.content().stream().map(block -> block instanceof TextBlock text ? text.text()
                    : block instanceof ToolResultBlock result ? result.text() : "").reduce("", String::concat))
                    .filter(text -> !text.startsWith("[Context update")).reduce((a, b) -> b).orElse("");
            if (last.equals("start")) {
                return new ModelOutcome.Replied(List.of(new ToolCallBlock("c1", "delegate", "{}",
                        new Json.Obj(Map.of("task", new Json.Str("count the files"))))), StopReason.TOOL_USE, "m", null);
            }
            if (last.contains("Task:\ncount the files")) {
                // 做不完的活：一直到被取消
                try {
                    Thread.sleep(60_000);
                } catch (InterruptedException cancelled) {
                    Thread.currentThread().interrupt();
                    return new ModelOutcome.Interrupted("");
                }
                return new ModelOutcome.Replied(List.of(new TextBlock("never")), StopReason.END_TURN, "m", null);
            }
            if (last.startsWith("Opened conversation ")) {
                opened.set(last.substring("Opened conversation ".length(), last.indexOf('.')));
                return new ModelOutcome.Replied(List.of(new TextBlock("handed off")), StopReason.END_TURN, "m", null);
            }
            if (last.equals("stop it")) {
                return new ModelOutcome.Replied(List.of(new ToolCallBlock("c2", "delegate", "{}",
                        new Json.Obj(Map.of("session", new Json.Str(opened.get()), "stop", new Json.Bool(true))))), StopReason.TOOL_USE, "m", null);
            }
            String reply = last.startsWith("Told conversation ") ? "taking it back" : "heard: " + last.lines().reduce((a, b) -> b).orElse("");
            return new ModelOutcome.Replied(List.of(new TextBlock(reply)), StopReason.END_TURN, "m", null);
        };
        try (TestServer server = new TestServer(temp.resolve("data4"), Files.createDirectory(temp.resolve("ws4")), window)) {
            server.post("alice", "/api/sessions/top/submissions", "{\"requestId\":\"r1\",\"mode\":\"prompt\",\"text\":\"start\"}");
            String messages = "";
            for (int i = 0; i < 1500 && !messages.contains("handed off"); i++) {
                Thread.sleep(10);
                messages = server.get("alice", "/api/sessions/top/messages").body();
            }
            assertTrue(messages.contains("handed off"), messages);
            server.post("alice", "/api/sessions/top/submissions", "{\"requestId\":\"r2\",\"mode\":\"prompt\",\"text\":\"stop it\"}");
            for (int i = 0; i < 1500 && !messages.contains("heard: "); i++) {
                Thread.sleep(10);
                messages = server.get("alice", "/api/sessions/top/messages").body();
            }
            assertTrue(messages.contains("heard: Conversation " + opened.get() + " did not finish: it was cancelled: taken back"), messages);
            JsonNode runs = JSON.readTree(server.get("alice", "/api/sessions/" + opened.get() + "/runs").body()).get("runs");
            assertEquals(1, runs.size());
            assertEquals("CANCELLED", runs.get(0).path("endReason").asString().toUpperCase(java.util.Locale.ROOT), runs.toString());
            List<JsonNode> back = users(JSON.readTree(messages));
            JsonNode answer = back.getLast();
            assertEquals(opened.get(), answer.path("from").path("session").asString());
            assertTrue(answer.path("from").path("reply").asBoolean() && answer.path("from").path("failed").asBoolean(), answer.toString());
            assertEquals(3, JSON.readTree(server.get("alice", "/api/sessions/top/runs").body()).get("runs").size(),
                    "the prompt, the request to stop, and the turn woken by the answer");
        }
    }

    /**
     * 会话编号是全服务器唯一的。一个用户的模型把活交给“另一个用户的会话编号”：那个会话一个字都不能多，
     * 交活的这边得到一句“没做成”，不会一直等。
     */
    @Test
    void workCannotBeHandedToAnotherUsersConversation() throws Exception {
        ModelClient window = (prompt, chunks) -> {
            String last = prompt.messages().stream().map(message -> message.content().stream().map(block -> block instanceof TextBlock text ? text.text()
                    : block instanceof ToolResultBlock result ? result.text() : "").reduce("", String::concat))
                    .filter(text -> !text.startsWith("[Context update")).reduce((a, b) -> b).orElse("");
            if (last.equals("reach over")) {
                return new ModelOutcome.Replied(List.of(new ToolCallBlock("c1", "delegate", "{}", new Json.Obj(Map.of(
                        "task", new Json.Str("say the secret"), "session", new Json.Str("alices"), "wait", new Json.Bool(true))))),
                        StopReason.TOOL_USE, "m", null);
            }
            return new ModelOutcome.Replied(List.of(new TextBlock(last.contains("Task:") ? "the secret is 7" : "got: " + last)),
                    StopReason.END_TURN, "m", null);
        };
        try (TestServer server = new TestServer(temp.resolve("data5"), Files.createDirectory(temp.resolve("ws5")), window)) {
            server.post("alice", "/api/sessions/alices/submissions", "{\"requestId\":\"r1\",\"mode\":\"prompt\",\"text\":\"hello\"}");
            String hers = "";
            for (int i = 0; i < 1500 && !hers.contains("got: hello"); i++) {
                Thread.sleep(10);
                hers = server.get("alice", "/api/sessions/alices/messages").body();
            }
            assertTrue(hers.contains("got: hello"), hers);
            server.post("bob", "/api/sessions/bobs/submissions", "{\"requestId\":\"r2\",\"mode\":\"prompt\",\"text\":\"reach over\"}");
            String his = "";
            for (int i = 0; i < 1500 && !his.contains("got: "); i++) {
                Thread.sleep(10);
                his = server.get("bob", "/api/sessions/bobs/messages").body();
            }
            assertTrue(his.contains("got: ") && !his.contains("the secret is 7"), "bob gets a refusal, not alice's answer: " + his);
            assertTrue(his.contains("did not finish"), his);
            hers = server.get("alice", "/api/sessions/alices/messages").body();
            assertTrue(!hers.contains("say the secret") && !hers.contains("the secret is 7"), "nothing arrived in alice's conversation: " + hers);
            assertEquals(1, JSON.readTree(server.get("alice", "/api/sessions/alices/runs").body()).get("runs").size());
        }
    }

    private static List<JsonNode> users(JsonNode page) {
        List<JsonNode> found = new java.util.ArrayList<>();
        for (JsonNode item : page.get("items")) {
            if (item.path("kind").asString().equals("user")) {
                found.add(item);
            }
        }
        return found;
    }

    /**
     * 进程死在“送话记了账、还没送进对方会话”之间：重启后这句话重送，对方开始干活，结果照样回来把交活的会话叫醒。
     * 再重启一次什么都不重复。
     */
    @Test
    void aMessageThatWasRecordedButNeverArrivedIsSentAgainAfterARestart() throws Exception {
        ModelClient window = (prompt, chunks) -> {
            String last = prompt.messages().stream().map(message -> message.content().stream().map(block -> block instanceof TextBlock text ? text.text()
                    : block instanceof ToolResultBlock result ? result.text() : "").reduce("", String::concat))
                    .filter(text -> !text.startsWith("[Context update")).reduce((a, b) -> b).orElse("");
            if (last.equals("start")) {
                return new ModelOutcome.Replied(List.of(new ToolCallBlock("c1", "delegate", "{\"task\":\"count the files\"}",
                        new Json.Obj(Map.of("task", new Json.Str("count the files"))))), StopReason.TOOL_USE, "m", null);
            }
            String reply = last.startsWith("Opened conversation") ? "handed off" : last.contains("Task:\ncount the files") ? "42 files"
                    : "the other conversation says: " + last.lines().reduce((a, b) -> b).orElse("");
            return new ModelOutcome.Replied(List.of(new TextBlock(reply)), StopReason.END_TURN, "m", null);
        };
        Path data = temp.resolve("data3");
        Path workspace = Files.createDirectory(temp.resolve("ws3"));
        System.setProperty("tantalum.test.drop-send-numbers", "1");
        try (TestServer server = new TestServer(data, workspace, window)) {
            server.post("alice", "/api/sessions/top/submissions", "{\"requestId\":\"r1\",\"mode\":\"prompt\",\"text\":\"start\"}");
            String messages = "";
            for (int i = 0; i < 1500 && !messages.contains("handed off"); i++) {
                Thread.sleep(10);
                messages = server.get("alice", "/api/sessions/top/messages").body();
            }
            assertTrue(messages.contains("handed off"), messages);
            Thread.sleep(300);
            assertEquals(1, JSON.readTree(server.get("alice", "/api/sessions").body()).get("sessions").size(), "it never arrived: no other conversation");
        } finally {
            System.clearProperty("tantalum.test.drop-send-numbers");
        }
        try (TestServer server = new TestServer(data, workspace, window)) {
            String messages = "";
            for (int i = 0; i < 1500 && !messages.contains("the other conversation says: 42 files"); i++) {
                Thread.sleep(10);
                messages = server.get("alice", "/api/sessions/top/messages").body();
            }
            assertTrue(messages.contains("the other conversation says: 42 files"), "sent again after the restart, done, and the answer came back: " + messages);
        }
        try (TestServer server = new TestServer(data, workspace, window)) {
            Thread.sleep(500);
            JsonNode sessions = JSON.readTree(server.get("alice", "/api/sessions").body()).get("sessions");
            assertEquals(2, sessions.size(), sessions.toString());
            assertEquals(2, JSON.readTree(server.get("alice", "/api/sessions/top/runs").body()).get("runs").size(), "nothing is repeated by another restart");
            for (JsonNode session : sessions) {
                if (!session.get("id").asString().equals("top")) {
                    assertEquals(1, JSON.readTree(server.get("alice", "/api/sessions/" + session.get("id").asString() + "/runs").body()).get("runs").size());
                }
            }
        }
    }
}
