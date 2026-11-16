package tantalum.shell.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolResultBlock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 模型自己安排在哪干活（2026-11-16 Yi）：新建工作区先问用户，同意了才登记；之后在那个工作区开一个会话，那个会话就在那个目录里；
 * 下一轮的上下文里看得到工作区和它下面的会话。用户说不建就不登记。
 */
class WorkspaceApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path temp;

    private final List<String> inputs = new CopyOnWriteArrayList<>();

    private final ModelClient model = (prompt, chunks) -> {
        inputs.add(prompt.toString());
        String last = prompt.messages().stream().map(message -> message.content().stream().map(block -> block instanceof TextBlock text ? text.text()
                : block instanceof ToolResultBlock result ? result.text() : "").reduce("", String::concat))
                // 上下文有变化时内核在后面追加一条说明，不是谁说的话
                .filter(text -> !text.startsWith("[Context update")).reduce((a, b) -> b).orElse("");
        if (last.equals("set up the shop")) {
            return call("c1", "workspace_add", Map.of("path", new Json.Str("/home/alice/shop"), "title", new Json.Str("Shop")));
        }
        if (last.startsWith("Added workspace")) {
            return call("c2", "delegate", Map.of("task", new Json.Str("list the files"), "workspace", new Json.Str("/home/alice/shop")));
        }
        String reply = last.startsWith("Opened conversation") ? "handed off" : last.contains("Task:\nlist the files") ? "3 files"
                : last.contains("decided not to add") ? "not added" : last.equals("what is there") ? "looked" : "back: " + last.lines().reduce((a, b) -> b).orElse("");
        return new ModelOutcome.Replied(List.of(new TextBlock(reply)), StopReason.END_TURN, "m", null);
    };

    private static ModelOutcome call(String id, String tool, Map<String, Json> arguments) {
        return new ModelOutcome.Replied(List.of(new ToolCallBlock(id, tool, "{}", new Json.Obj(arguments))), StopReason.TOOL_USE, "m", null);
    }

    /** 等到对话页上出现问题卡片，答掉它；返回卡片。 */
    private static JsonNode answer(TestServer server, String session, String choice) throws Exception {
        for (int i = 0; i < 1500; i++) {
            JsonNode questions = JSON.readTree(server.get("alice", "/api/sessions/" + session + "/questions").body()).path("questions");
            if (questions.size() > 0) {
                JsonNode question = questions.get(0);
                server.post("alice", "/api/sessions/" + session + "/questions/" + question.get("id").asString(), "{\"choice\":\"" + choice + "\"}");
                return question;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("no question appeared");
    }

    private static String waitFor(TestServer server, String session, String text) throws Exception {
        String messages = "";
        for (int i = 0; i < 1500 && !messages.contains(text); i++) {
            Thread.sleep(10);
            messages = server.get("alice", "/api/sessions/" + session + "/messages").body();
        }
        return messages;
    }

    @Test
    void theUserConfirmsANewWorkspaceAndAConversationIsOpenedInIt() throws Exception {
        try (TestServer server = new TestServer(temp.resolve("data"), Files.createDirectory(temp.resolve("ws")), model)) {
            server.post("alice", "/api/sessions/top/submissions", "{\"requestId\":\"r1\",\"mode\":\"prompt\",\"text\":\"set up the shop\"}");
            JsonNode question = answer(server, "top", "add");
            assertTrue(question.get("id").asString().startsWith("workspace-"), question.toString());
            assertTrue(question.toString().contains("/home/alice/shop") && question.toString().contains("Shop"), "the card shows the folder and the name");

            String messages = waitFor(server, "top", "back: 3 files");
            assertTrue(messages.contains("back: 3 files"), "added, handed off, and the answer came back: " + messages);
            JsonNode projects = JSON.readTree(server.get("alice", "/api/projects").body()).get("projects");
            assertEquals(1, projects.size(), projects.toString());
            assertEquals("Shop", projects.get(0).get("title").asString());
            assertEquals("/home/alice/shop", projects.get(0).get("path").asString());

            JsonNode sessions = JSON.readTree(server.get("alice", "/api/sessions").body()).get("sessions");
            assertEquals(2, sessions.size(), sessions.toString());
            String opened = null;
            for (JsonNode session : sessions) {
                if (!session.get("id").asString().equals("top")) {
                    opened = session.get("id").asString();
                    assertEquals("/home/alice/shop", session.path("dir").asString(), "the new conversation works in the workspace's folder");
                    assertEquals("top", session.path("parent").asString());
                } else {
                    assertTrue(session.path("dir").isNull() || session.path("dir").isMissingNode(), "the opener stays where it was");
                }
            }

            // 下一轮：上下文里有工作区和它下面那个会话（这一段同一个人一秒内用上一次查到的，所以等一下）
            Thread.sleep(1500);
            server.post("alice", "/api/sessions/top/submissions", "{\"requestId\":\"r2\",\"mode\":\"prompt\",\"text\":\"what is there\"}");
            assertTrue(waitFor(server, "top", "looked").contains("looked"));
            String seen = inputs.getLast();
            assertTrue(seen.contains("- workspace \"Shop\" — /home/alice/shop") || seen.contains("- workspace \\\"Shop\\\" — /home/alice/shop"),
                    "the model is told about the workspace: " + seen);
            assertTrue(seen.contains("- " + opened + " \"list the files\" · in \"Shop\"") || seen.contains("- " + opened + " \\\"list the files\\\" · in \\\"Shop\\\""),
                    "and about the conversation in it, by its task: " + seen);
        }
    }

    @Test
    void whenTheUserSaysNoNothingIsAdded() throws Exception {
        try (TestServer server = new TestServer(temp.resolve("data2"), Files.createDirectory(temp.resolve("ws2")), model)) {
            server.post("alice", "/api/sessions/top/submissions", "{\"requestId\":\"r1\",\"mode\":\"prompt\",\"text\":\"set up the shop\"}");
            assertEquals(0, JSON.readTree(server.get("alice", "/api/projects").body()).get("projects").size(), "nothing before the answer");
            answer(server, "top", "deny");
            String messages = waitFor(server, "top", "not added");
            assertTrue(messages.contains("not added"), messages);
            assertEquals(0, JSON.readTree(server.get("alice", "/api/projects").body()).get("projects").size());
            assertEquals(1, JSON.readTree(server.get("alice", "/api/sessions").body()).get("sessions").size(), "and no conversation was opened");
            assertFalse(inputs.stream().anyMatch(input -> input.contains("Added workspace")));
        }
    }
}
