package tantalum.shell.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.cli.CliText;
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
import tantalum.value.model.ToolResultBlock;
import tantalum.value.text.Language;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 待办清单的整条链路（todo-tool 任务 4；需求 1、2、4）：真服务器、脚本模型。模型写清单、写错一次、再写一张替换；
 * 结果原样回给模型；对话条目里带着每次调用；记录重放一致；手动压缩后下一次请求的摘要里附着最后那张清单。
 */
class TodoChainTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String LONG = "x".repeat(400_000);

    @TempDir
    Path temp;

    private final List<Prompt> prompts = Collections.synchronizedList(new ArrayList<>());
    private TestServer server;

    private static Json todos(String... items) {
        List<Json> list = new ArrayList<>();
        for (String item : items) {
            String[] parts = item.split(":", 2);
            Map<String, Json> fields = new LinkedHashMap<>();
            fields.put("content", new Json.Str(parts[1]));
            fields.put("status", new Json.Str(parts[0]));
            list.add(new Json.Obj(fields));
        }
        return new Json.Obj(Map.of("todos", new Json.Arr(list)));
    }

    private static ModelOutcome call(String id, Json arguments) {
        return new ModelOutcome.Replied(List.of(new ToolCallBlock(id, "todo_write", "{}", arguments)), StopReason.TOOL_USE, "m", null);
    }

    private static ModelOutcome text(String text) {
        return new ModelOutcome.Replied(List.of(new TextBlock(text)), StopReason.END_TURN, "m", null);
    }

    private static String lastText(Prompt prompt) {
        ContentBlock last = prompt.messages().getLast().content().getLast();
        return last instanceof TextBlock block ? block.text() : "";
    }

    /** 提问 plan：写清单 → 写错（两项在做）→ 换一张 → 回答；提问 long：回一大段（让压缩有东西可压）；摘要请求：回摘要。 */
    private final ModelClient model = (prompt, chunks) -> {
        prompts.add(prompt);
        if (lastText(prompt).contains("is about to be replaced by a summary")) {
            return text("The user asked for a plan; it is being worked through.");
        }
        long results = prompt.messages().stream().flatMap(message -> message.content().stream()).filter(ToolResultBlock.class::isInstance).count();
        boolean planning = prompt.messages().stream().anyMatch(message -> message.content().stream()
                .anyMatch(block -> block instanceof TextBlock t && t.text().equals("plan")));
        if (planning && results == 0) {
            return call("t1", todos("in_progress:Read the failing test", "pending:Fix apply_discount", "pending:Run all tests"));
        }
        if (planning && results == 1) {
            return call("t2", todos("in_progress:Read the failing test", "in_progress:Fix apply_discount"));
        }
        if (planning && results == 2) {
            return call("t3", todos("completed:Read the failing test", "in_progress:Fix apply_discount", "pending:Run all tests"));
        }
        if (lastText(prompt).equals("long")) {
            return text(LONG);
        }
        return text("done");
    };

    @BeforeEach
    void start() {
        server = new TestServer(temp.resolve("data"), temp.resolve("ws"), model);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    /** {@code runs}：这次提问结束后会话里一共有几轮（手动压缩自己也算一轮）。 */
    private void ask(String text, int runs) throws Exception {
        String body = "{\"requestId\":\"r" + runs + "\",\"mode\":\"prompt\",\"text\":\"" + text + "\"}";
        assertEquals(200, server.post("alice", "/api/sessions/s1/submissions", body).statusCode());
        waitForRuns(runs);
    }

    private void waitForRuns(int count) throws Exception {
        for (int i = 0; i < 1500; i++) {
            JsonNode runs = JSON.readTree(server.get("alice", "/api/sessions/s1/runs").body()).get("runs");
            if (runs != null && runs.size() == count && runs.get(count - 1).has("endReason")) {
                assertEquals("COMPLETED", runs.get(count - 1).get("endReason").asString(), runs.toString());
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("run " + count + " did not end: " + server.get("alice", "/api/sessions/s1/runs").body());
    }

    private List<ToolResultBlock> results(Prompt prompt) {
        return prompt.messages().stream().flatMap(message -> message.content().stream())
                .filter(ToolResultBlock.class::isInstance).map(ToolResultBlock.class::cast).toList();
    }

    @Test
    void theModelWritesReplacesAndRecoversFromAnInvalidList() throws Exception {
        ask("plan", 1);
        assertTrue(prompts.getFirst().tools().stream().anyMatch(spec -> spec.name().equals("todo_write")), "offered by default");
        List<ToolResultBlock> seen = results(prompts.getLast());
        assertEquals(3, seen.size());
        assertEquals("Todo list updated: 0 of 3 completed; in progress: Read the failing test.", seen.get(0).text());
        assertTrue(seen.get(1).error());
        assertEquals("Invalid todos: at most one item may be in_progress (got 2).", seen.get(1).text());
        assertEquals("Todo list updated: 1 of 3 completed; in progress: Fix apply_discount.", seen.get(2).text());

        // 对话条目里带着每一次调用和结果（页面从这里算横条）
        JsonNode items = JSON.readTree(server.get("alice", "/api/sessions/s1/messages").body()).get("items");
        List<JsonNode> calls = new ArrayList<>();
        for (JsonNode item : items) {
            if (item.get("kind").asString().equals("tool") && item.get("call").get("name").asString().equals("todo_write")) {
                calls.add(item);
            }
        }
        assertEquals(3, calls.size(), items.toString());
        assertTrue(calls.get(1).get("result").get("error").asBoolean());
        assertEquals("Fix apply_discount", calls.get(2).get("call").get("arguments").get("todos").get(1).get("content").asString());

        // 记录重放一致
        server.close();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code = RecordCheckCommand.run(new String[] {"--tantalum.data-dir=" + temp.resolve("data"), "--tantalum.workspace=" + temp.resolve("ws")},
                new PrintStream(out, true, StandardCharsets.UTF_8), CliText.of(Language.EN));
        assertEquals(0, code, out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void afterCompactionTheSummaryCarriesTheLatestList() throws Exception {
        ask("plan", 1);
        ask("long", 2);
        ask("long", 3);
        var compact = server.post("alice", "/api/sessions/s1/compact", "{\"requestId\":\"c1\"}");
        assertEquals(200, compact.statusCode(), compact.body());
        waitForRuns(4);
        JsonNode runs = JSON.readTree(server.get("alice", "/api/sessions/s1/runs").body()).get("runs");
        assertEquals("compacted", runs.get(3).get("detail").asString(), runs.toString());
        ask("next", 5);
        Message first = prompts.getLast().messages().getFirst();
        String summary = ((TextBlock) first.content().getFirst()).text();
        assertTrue(summary.startsWith("[Summary of the earlier conversation"), summary.substring(0, Math.min(200, summary.length())));
        assertTrue(summary.endsWith("<todo-list>\n[x] Read the failing test\n[>] Fix apply_discount\n[ ] Run all tests\n</todo-list>"), summary);
        assertFalse(summary.contains("Invalid todos"), "the refused list is not the current one");
    }
}
