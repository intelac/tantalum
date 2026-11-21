package tantalum.shell.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import tantalum.value.assignment.AssignmentTools;
import tantalum.value.json.Json;
import tantalum.value.model.NoticeBlock;
import tantalum.value.model.Prompt;
import tantalum.value.model.ReportBlock;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolResultBlock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * dispatch 端到端，走真的服务器：主窗口（main-alice）只有 task、task_stop；它派的活开在一个普通会话里（侧栏列得出、能直接说话），
 * 结果回到主窗口；你在那个会话里直接说的话那边照常回答，主窗口记一笔，不为它调模型；再重启一次什么都不多。
 */
class DispatchChainTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path temp;

    private final List<Prompt> mainPrompts = Collections.synchronizedList(new ArrayList<>());
    private final List<Prompt> workPrompts = Collections.synchronizedList(new ArrayList<>());
    private TestServer server;

    private static ModelOutcome text(String text) {
        return new ModelOutcome.Replied(List.of(new TextBlock(text)), StopReason.END_TURN, "m", null);
    }

    private static <T> List<T> blocks(Prompt prompt, Class<T> type) {
        return prompt.messages().stream().flatMap(message -> message.content().stream()).filter(type::isInstance).map(type::cast).toList();
    }

    /** 主窗口：第一次派活，拿到“已开始”后说一句；看到结果后转述。派活会话：交代来的答“Module A is fine.”，你直接说的答“B is fine too.”。 */
    private final ModelClient model = (prompt, chunks) -> {
        if (prompt.system().contains("## Main window")) {
            mainPrompts.add(prompt);
            List<ReportBlock> back = blocks(prompt, ReportBlock.class);
            if (!back.isEmpty()) {
                return text("The job reported: " + back.getLast().text());
            }
            if (blocks(prompt, ToolResultBlock.class).isEmpty()) {
                return new ModelOutcome.Replied(List.of(new ToolCallBlock("c1", AssignmentTools.TASK, "{}",
                        new Json.Obj(Map.of(AssignmentTools.PROMPT, new Json.Str("Check module A."))))), StopReason.TOOL_USE, "m", null);
            }
            return text("Handed out.");
        }
        workPrompts.add(prompt);
        String last = prompt.messages().getLast().content().stream().filter(TextBlock.class::isInstance).map(TextBlock.class::cast)
                .map(TextBlock::text).findFirst().orElse("");
        return text(last.contains("also check B") ? "B is fine too." : "Module A is fine.");
    };

    @BeforeEach
    void start() {
        server = new TestServer(temp.resolve("data"), temp.resolve("ws"), model);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private JsonNode runs(String session) throws Exception {
        return JSON.readTree(server.get("alice", "/api/sessions/" + session + "/runs").body()).get("runs");
    }

    private JsonNode waitForRuns(String session, int count) throws Exception {
        for (int i = 0; i < 1500; i++) {
            JsonNode runs = runs(session);
            if (runs != null && runs.size() >= count && runs.get(count - 1).has("endReason")) {
                return runs;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("run " + count + " of " + session + " did not end: " + runs(session));
    }

    private JsonNode items(String session) throws Exception {
        return JSON.readTree(server.get("alice", "/api/sessions/" + session + "/messages").body()).get("items");
    }

    @Test
    void theMainWindowHandsOutAJobTheJobSessionIsListedAndWhatYouSayThereIsNoted() throws Exception {
        assertEquals(200, server.post("alice", "/api/sessions/main-alice/submissions",
                "{\"requestId\":\"r1\",\"delivery\":\"queue\",\"text\":\"look into module A\"}").statusCode());
        // 第一轮派活，第二轮是结果回来开的
        JsonNode mainRuns = waitForRuns("main-alice", 2);
        assertEquals("COMPLETED", mainRuns.get(1).get("endReason").asString(), mainRuns.toString());

        // 主窗口只有交代和叫停
        assertEquals(List.of("task", "task_stop"), mainPrompts.getFirst().tools().stream().map(spec -> spec.name()).sorted().toList());
        assertTrue(blocks(mainPrompts.getLast(), ReportBlock.class).getLast().text().contains("Module A is fine."));

        // 派活会话：普通会话，列在侧栏里；主窗口自己不列
        JsonNode sessions = JSON.readTree(server.get("alice", "/api/sessions").body()).get("sessions");
        List<String> listed = new ArrayList<>();
        sessions.forEach(each -> listed.add(each.get("id").asString()));
        String work = listed.stream().filter(id -> id.startsWith("work-alice-")).findFirst().orElseThrow(() -> new AssertionError(listed.toString()));
        assertFalse(listed.contains("main-alice"), listed.toString());
        assertTrue(workPrompts.getFirst().tools().stream().anyMatch(spec -> !spec.name().startsWith("task")), "a regular conversation has its tools");

        // 你直接在那边说：那边照常回答；主窗口记一笔，不开轮
        int mainCalls = mainPrompts.size();
        assertEquals(200, server.post("alice", "/api/sessions/" + work + "/submissions",
                "{\"requestId\":\"r2\",\"delivery\":\"queue\",\"text\":\"also check B\"}").statusCode());
        waitForRuns(work, 2);
        JsonNode note = null;
        for (int i = 0; i < 1500 && note == null; i++) {
            for (JsonNode item : items("main-alice")) {
                if ("note".equals(item.get("kind").asString())) {
                    note = item;
                }
            }
            if (note == null) {
                Thread.sleep(10);
            }
        }
        assertTrue(note != null, items("main-alice").toString());
        assertEquals(work, note.get("from").asString());
        assertTrue(note.get("text").asString().contains("also check B") && note.get("text").asString().contains("B is fine too."));
        Thread.sleep(200);
        assertEquals(mainCalls, mainPrompts.size(), "no model call for a note");
        assertEquals(2, runs("main-alice").size());

        // 人不能先占派活的编号、别人的主窗口
        assertEquals(409, server.post("alice", "/api/sessions/work-alice-99-0/submissions",
                "{\"requestId\":\"r3\",\"delivery\":\"queue\",\"text\":\"hi\"}").statusCode());
        assertEquals(409, server.post("alice", "/api/sessions/main-bob/submissions",
                "{\"requestId\":\"r4\",\"delivery\":\"queue\",\"text\":\"hi\"}").statusCode());

        // 下一次主窗口调模型时看得到那一笔
        assertEquals(200, server.post("alice", "/api/sessions/main-alice/submissions",
                "{\"requestId\":\"r5\",\"delivery\":\"queue\",\"text\":\"anything new?\"}").statusCode());
        waitForRuns("main-alice", 3);
        assertFalse(blocks(mainPrompts.getLast(), NoticeBlock.class).isEmpty(), "the note is in the next call");
    }
}
