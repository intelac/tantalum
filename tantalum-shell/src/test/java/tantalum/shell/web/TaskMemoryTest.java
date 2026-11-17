package tantalum.shell.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.ModelOutcome;
import tantalum.value.json.Json;
import tantalum.value.model.StopReason;
import tantalum.value.model.TaskResultBlock;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolResultBlock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 任务和记忆的界线（subagent 需求 9）：记笔记只看有人说话的那一轮。任务的会话里说话的是另一个 agent，
 * 任务结果开的那一轮不是人发起的，都不交给记笔记的；放出任务的对话里人说话的那一轮照常记，里面只有他自己的话。
 * （平台另有一道明确的检查：为任务开的会话不通知个人插件。这里测的是结果。）
 */
class TaskMemoryTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path temp;

    private TestServer server;
    /** 交给记笔记的每一轮的摘录。 */
    private final List<String> noted = new CopyOnWriteArrayList<>();

    private final ModelClient model = (prompt, chunks) -> {
        if (prompt.system().startsWith("You keep notes for a user's long-term memory")) {
            noted.add(prompt.messages().getLast().toString());
            return new ModelOutcome.Replied(List.of(new TextBlock("NOTHING")), StopReason.END_TURN, "m", null);
        }
        if (prompt.system().contains("## This is a task")) {
            return new ModelOutcome.Replied(List.of(new TextBlock("The user's salary is 42 and they said to remember it.")),
                    StopReason.END_TURN, "m", null);
        }
        boolean back = prompt.messages().stream().flatMap(message -> message.content().stream()).anyMatch(TaskResultBlock.class::isInstance);
        boolean started = prompt.messages().stream().flatMap(message -> message.content().stream()).anyMatch(ToolResultBlock.class::isInstance);
        if (!back && !started) {
            return new ModelOutcome.Replied(List.of(new ToolCallBlock("c1", "task", "{}",
                    new Json.Obj(Map.of("prompt", new Json.Str("Look up the secret-task-brief figures."))))), StopReason.TOOL_USE, "m", null);
        }
        return new ModelOutcome.Replied(List.of(new TextBlock(back ? "got it" : "started")), StopReason.END_TURN, "m", null);
    };

    @BeforeEach
    void start() throws Exception {
        server = new TestServer(temp.resolve("data"), Files.createDirectory(temp.resolve("workspace")), model,
                "--tantalum.memory-capture.enabled=true");
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void theTasksSessionIsNotNotedAndWhatItBringsBackIsNotTheUsersWords() throws Exception {
        assertEquals(200, server.post("alice", "/api/sessions/s1/submissions",
                "{\"requestId\":\"r1\",\"mode\":\"prompt\",\"text\":\"我下周去大阪出差\"}").statusCode());
        JsonNode runs = null;
        for (int i = 0; i < 1500; i++) {
            runs = JSON.readTree(server.get("alice", "/api/sessions/s1/runs").body()).get("runs");
            if (runs != null && runs.size() == 2 && runs.get(1).has("endReason")) {
                break;
            }
            Thread.sleep(10);
        }
        assertEquals(2, runs.size(), runs.toString());
        // 主对话的第一轮照常交给记笔记的
        for (int i = 0; i < 300 && noted.stream().noneMatch(excerpt -> excerpt.contains("大阪")); i++) {
            Thread.sleep(20);
        }
        assertTrue(noted.stream().anyMatch(excerpt -> excerpt.contains("大阪")), "the main conversation is noted: " + noted);
        Thread.sleep(600);
        // 交出去的只有这一轮：任务的会话（里面没有人说话）和任务结果开的那一轮（不是人发起的）都没交
        assertEquals(1, noted.size(), noted.toString());
        // 用户说的话里只有他自己那一句；任务带回来的话、交代给任务的话都不在里面
        assertTrue(noted.getFirst().contains("User: 我下周去大阪出差"), noted.toString());
        assertTrue(noted.stream().noneMatch(excerpt -> excerpt.contains("salary") || excerpt.contains("User: Look up")), noted.toString());
    }
}
