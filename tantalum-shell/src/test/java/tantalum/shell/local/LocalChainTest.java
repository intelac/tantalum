package tantalum.shell.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.ModelOutcome;
import tantalum.shell.web.TestServer;
import tantalum.value.json.Json;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.Message;
import tantalum.value.model.Prompt;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolChangeBlock;
import tantalum.value.model.ToolResultBlock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 内核链路（local-workspace 需求 5、L8、L9）：真服务器、测试电脑。电脑连上，下一次提问模型收到“新增”的工具变化；断开，下一次提问收到
 * “不能用了”，对话里那一轮上方各有一条；调用途中断开，模型收到“结果不确定”的错误，运行照常结束。工具定义从头到尾不变。
 */
class LocalChainTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<String> READ = List.of("find_files", "grep", "list_dir", "read_file");

    @TempDir
    Path temp;

    private final List<Prompt> prompts = Collections.synchronizedList(new ArrayList<>());
    private TestServer server;
    private Path mac;

    /** 提问是 {@code read} 时调 read_file，拿到结果后原样回答；其余直接回答。 */
    private final ModelClient model = (prompt, chunks) -> {
        prompts.add(prompt);
        ContentBlock last = prompt.messages().getLast().content().getLast();
        if (last instanceof ToolResultBlock result) {
            return new ModelOutcome.Replied(List.of(new TextBlock((result.error() ? "ERROR: " : "OK: ") + result.text())),
                    StopReason.END_TURN, "m", null);
        }
        if (last instanceof TextBlock text && text.text().startsWith("read")) {
            return new ModelOutcome.Replied(List.of(new ToolCallBlock("c-" + prompts.size(), "read_file", "{\"path\":\"a.txt\"}",
                    new Json.Obj(Map.of("path", new Json.Str("a.txt"))))), StopReason.TOOL_USE, "m", null);
        }
        return new ModelOutcome.Replied(List.of(new TextBlock("ok")), StopReason.END_TURN, "m", null);
    };

    @BeforeEach
    void start() throws Exception {
        mac = Files.createDirectories(temp.resolve("mac"));
        Files.writeString(mac.resolve("a.txt"), "hello from the mac");
        server = new TestServer(temp.resolve("data"), temp.resolve("ws"), model);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private void ask(String text, int run) throws Exception {
        String body = "{\"requestId\":\"r" + run + "\",\"mode\":\"prompt\",\"text\":\"" + text + "\"}";
        assertEquals(200, server.post("alice", "/api/sessions/s1/submissions", body).statusCode());
        for (int i = 0; i < 1000; i++) {
            JsonNode runs = JSON.readTree(server.get("alice", "/api/sessions/s1/runs").body()).get("runs");
            if (runs != null && runs.size() == run && runs.get(run - 1).has("endReason")) {
                assertEquals("COMPLETED", runs.get(run - 1).get("endReason").asString(), runs.toString());
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("run " + run + " did not end");
    }

    /** 历史里最新的一条工具变化（内核在提问时追加），没有时为 null。 */
    private static ToolChangeBlock change(Prompt prompt) {
        ToolChangeBlock latest = null;
        for (Message message : prompt.messages()) {
            for (ContentBlock block : message.content()) {
                if (block instanceof ToolChangeBlock change) {
                    latest = change;
                }
            }
        }
        return latest;
    }

    private static List<String> sorted(List<String> names) {
        return names.stream().filter(READ::contains).sorted().toList();
    }

    private List<JsonNode> toolChanges() throws Exception {
        List<JsonNode> changes = new ArrayList<>();
        for (JsonNode item : JSON.readTree(server.get("alice", "/api/sessions/s1/messages").body()).get("items")) {
            if (item.get("kind").asString().equals("toolChange")) {
                changes.add(item);
            }
        }
        return changes;
    }

    @Test
    void connectingAndDisconnectingShowUpAsToolChangesOnTheNextQuestion() throws Exception {
        ask("hello", 1);
        Prompt first = prompts.getLast();
        assertTrue(first.tools().stream().filter(tool -> READ.contains(tool.name())).allMatch(tool -> tool.deferred()),
                "not connected: the tools are in the full set but not offered");
        assertFalse(first.system().contains("<local-workspace>"));

        TestComputer computer = server.connectComputer("alice", mac, false);
        ask("hello again", 2);
        Prompt second = prompts.getLast();
        ToolChangeBlock added = change(second);
        assertNotNull(added, "the model is told what was added");
        assertEquals(READ, sorted(added.added()));
        assertEquals(first.tools().stream().map(tool -> tool.name()).toList(), second.tools().stream().map(tool -> tool.name()).toList(),
                "tool definitions do not change (L9)");
        assertTrue(second.messages().toString().contains("Local workspace: macOS"), "the local-workspace section is appended as an update");

        computer.disconnect();
        ask("and now", 3);
        ToolChangeBlock removed = change(prompts.getLast());
        assertNotNull(removed);
        assertEquals(READ, sorted(removed.removed()));

        List<JsonNode> items = toolChanges();
        assertEquals(2, items.size(), items.toString());
        assertTrue(items.get(0).get("added").toString().contains("read_file"), items.get(0).toString());
        assertTrue(items.get(1).get("disabled").toString().contains("read_file"), items.get(1).toString());
        assertEquals("local", items.get(1).get("sources").get("read_file").asString(), "the console explains it with the source");
    }

    @Test
    void aCallOnTheComputerWorksAndADisconnectInTheMiddleIsAnUnknownOutcome() throws Exception {
        TestComputer computer = server.connectComputer("alice", mac, false);
        ask("read it", 1);
        assertTrue(prompts.getLast().messages().toString().contains("hello from the mac"), "the result came from the computer");

        computer.holdCalls();
        Thread disconnect = Thread.ofVirtual().start(() -> {
            try {
                assertNotNull(computer.nextCall(10));
                computer.disconnect();
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            }
        });
        ask("read it again", 2);
        disconnect.join();
        String answer = prompts.getLast().messages().toString();
        assertTrue(answer.contains("The computer disconnected. Whether this operation finished is unknown."), answer);
    }
}
