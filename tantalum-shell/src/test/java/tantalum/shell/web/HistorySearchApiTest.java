package tantalum.shell.web;

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

/** memory-final-plan 第 9 节：history_search 也搜工具调用的结果；侧栏按内容搜不搜它（session-find-pin R3 只算你和模型说的话）。 */
class HistorySearchApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path temp;

    private final List<String> inputs = new CopyOnWriteArrayList<>();

    /** 第一轮读文件，第二轮搜历史；拿到工具结果后回一句。 */
    private final ModelClient model = (prompt, chunks) -> {
        inputs.add(prompt.toString());
        boolean answered = prompt.messages().getLast().content().stream().anyMatch(ToolResultBlock.class::isInstance);
        if (answered) {
            return new ModelOutcome.Replied(List.of(new TextBlock("好的。")), StopReason.END_TURN, "m", null);
        }
        String asked = prompt.messages().getLast().content().stream().filter(TextBlock.class::isInstance).map(block -> ((TextBlock) block).text())
                .findFirst().orElse("");
        ToolCallBlock call = asked.contains("读一下")
                ? new ToolCallBlock("c1", "read_file", "{\"path\":\"notes.txt\"}", new Json.Obj(Map.of("path", new Json.Str("notes.txt"))))
                : new ToolCallBlock("c2", "history_search", "{\"query\":\"zebra-amber-42\"}",
                        new Json.Obj(Map.of("query", new Json.Str("zebra-amber-42"))));
        return new ModelOutcome.Replied(List.of(call), StopReason.TOOL_USE, "m", null);
    };

    private void ask(TestServer server, String session, String text) throws Exception {
        server.post("alice", "/api/sessions/" + session + "/submissions", "{\"requestId\":\"" + session + "\",\"mode\":\"prompt\",\"text\":\"" + text + "\"}");
        for (int i = 0; i < 1000; i++) {
            JsonNode runs = JSON.readTree(server.get("alice", "/api/sessions/" + session + "/runs").body()).get("runs");
            if (runs != null && runs.size() > 0 && runs.get(runs.size() - 1).has("endReason")) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("the run did not end");
    }

    @Test
    void historySearchFindsWhatAToolReadInAnotherSession() throws Exception {
        Path dir = Files.createDirectories(temp.resolve("project"));
        Files.writeString(dir.resolve("notes.txt"), "部署口令的提示词是 zebra-amber-42\n");
        try (TestServer server = new TestServer(temp.resolve("data"), Files.createDirectory(temp.resolve("ws")), model)) {
            try (var computer = server.connectComputer("alice", dir, false)) {
                ask(server, "first", "读一下 notes.txt");
                for (int i = 0; i < 300 && !server.get("alice", "/api/sessions/search?q=notes").body().contains("first"); i++) {
                    Thread.sleep(20);
                }
                Thread.sleep(300);
                // 另一个会话也读过同一个文件：两条命中次数一样（2026-11-16 端到端测出排序拿没有的时刻去比，抛了空指针）
                ask(server, "again", "读一下 notes.txt");
                Thread.sleep(300);
                ask(server, "second", "以前那个提示词是什么？");
            }
            assertTrue(inputs.getLast().contains("read_file: 部署口令的提示词是 zebra-amber-42"), "history_search found the tool result");
            assertFalse(inputs.getLast().contains("Tool failed"), inputs.getLast());
            assertTrue(inputs.getLast().contains("[first · tool]") && inputs.getLast().contains("[again · tool]"), "both, without a missing time: "
                    + inputs.getLast().substring(Math.max(0, inputs.getLast().length() - 500)));
            assertFalse(server.get("alice", "/api/sessions/search?q=zebra-amber-42").body().contains("\"first\""),
                    "the sidebar search covers what was said, not tool output");
        }
    }
}
