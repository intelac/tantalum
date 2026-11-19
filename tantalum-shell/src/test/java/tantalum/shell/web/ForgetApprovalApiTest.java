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
 * 忘掉之前先问用户（2026-11-20 Yi）：聊着发现记忆不对，模型查出候选、要真的去掉时，对话页上先给用户看要去掉的原文；
 * 用户说留着就什么都不动，说忘掉才去掉。只是找候选不问。
 */
class ForgetApprovalApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final java.util.regex.Pattern ID = java.util.regex.Pattern.compile("(?m)^- id (\\S+) ");

    @TempDir
    Path temp;

    private final ModelClient model = (prompt, chunks) -> {
        String last = prompt.messages().stream().map(message -> message.content().stream().map(block -> block instanceof TextBlock text ? text.text()
                : block instanceof ToolResultBlock result ? result.text() : "").reduce("", String::concat))
                .filter(text -> !text.startsWith("[Context update") && !text.startsWith("<memory-recall>")).reduce((a, b) -> b).orElse("");
        if (last.equals("my plate is wrong, forget it")) {
            return call("c1", Map.of("text", new Json.Str("plate is 7Q2KD")));
        }
        if (last.startsWith("Nothing was changed yet")) {
            List<Json> ids = new java.util.ArrayList<>();
            java.util.regex.Matcher found = ID.matcher(last);
            while (found.find()) {
                ids.add(new Json.Str(found.group(1)));
            }
            return call("c2", Map.of("places", new Json.Arr(ids)));
        }
        String reply = last.contains("decided to keep") ? "kept it" : last.startsWith("Forgotten") ? "it is gone" : "back: " + last;
        return new ModelOutcome.Replied(List.of(new TextBlock(reply)), StopReason.END_TURN, "m", null);
    };

    private static ModelOutcome call(String id, Map<String, Json> arguments) {
        return new ModelOutcome.Replied(List.of(new ToolCallBlock(id, "memory_delete", "{}", new Json.Obj(arguments))), StopReason.TOOL_USE, "m", null);
    }

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

    private static String plate(TestServer server) throws Exception {
        return server.get("alice", "/api/plugin-api/memory/entries/car").body();
    }

    private static void remember(TestServer server) throws Exception {
        server.post("alice", "/api/plugin-api/memory/entries",
                "{\"name\":\"car\",\"type\":\"user\",\"description\":\"the car\",\"content\":\"My plate is 7Q2KD\\nThe car is blue\"}");
        assertTrue(plate(server).contains("7Q2KD"), plate(server));
    }

    @Test
    void theModelFindsTheLinesAndTheUserSeesThemBeforeAnythingIsRemoved() throws Exception {
        try (TestServer server = new TestServer(temp.resolve("data"), Files.createDirectory(temp.resolve("ws")), model)) {
            remember(server);
            server.post("alice", "/api/sessions/top/submissions", "{\"requestId\":\"r1\",\"mode\":\"prompt\",\"text\":\"my plate is wrong, forget it\"}");
            JsonNode question = answer(server, "top", "forget");
            assertTrue(question.get("id").asString().startsWith("memory-forget-"), question.toString());
            assertTrue(question.toString().contains("My plate is 7Q2KD"), "the card shows the exact line: " + question);
            String messages = waitFor(server, "top", "it is gone");
            assertTrue(messages.contains("it is gone"), messages);
            String left = plate(server);
            assertFalse(left.contains("7Q2KD"), "the confirmed line is gone: " + left);
        }
    }

    @Test
    void whenTheUserSaysKeepNothingIsRemoved() throws Exception {
        try (TestServer server = new TestServer(temp.resolve("data2"), Files.createDirectory(temp.resolve("ws2")), model)) {
            remember(server);
            server.post("alice", "/api/sessions/top/submissions", "{\"requestId\":\"r1\",\"mode\":\"prompt\",\"text\":\"my plate is wrong, forget it\"}");
            answer(server, "top", "keep");
            String messages = waitFor(server, "top", "kept it");
            assertTrue(messages.contains("kept it"), messages);
            assertTrue(plate(server).contains("7Q2KD"), "still there");
        }
    }
}
