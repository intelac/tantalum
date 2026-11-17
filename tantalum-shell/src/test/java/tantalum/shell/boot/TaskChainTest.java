package tantalum.shell.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.ModelOutcome;
import tantalum.shell.web.TestServer;
import tantalum.value.json.Json;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.Prompt;
import tantalum.value.model.StopReason;
import tantalum.value.model.TaskResultBlock;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolResultBlock;
import tantalum.value.task.TaskTools;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 任务的整条链路（subagent 需求 1–4、10.1）：真服务器、脚本模型、两个会话。主会话放出任务后这一轮照常结束；
 * 任务在自己的会话里做完，结果回到主会话，开一轮不是人发起的运行；任务的会话不出现在会话清单里；重启后一切照旧。
 */
class TaskChainTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern STARTED = Pattern.compile("Started task (t\\d+-\\d+)");

    @TempDir
    Path temp;

    /** 主会话的模型请求和任务会话的模型请求分开记。 */
    private final List<Prompt> mainPrompts = Collections.synchronizedList(new ArrayList<>());
    private final List<Prompt> taskPrompts = Collections.synchronizedList(new ArrayList<>());
    private TestServer server;
    /** 为真时任务先问用户一个问题，停在等回答。 */
    private volatile boolean taskAsks;

    private static ModelOutcome text(String text) {
        return new ModelOutcome.Replied(List.of(new TextBlock(text)), StopReason.END_TURN, "m", null);
    }

    private static <T> List<T> blocks(Prompt prompt, Class<T> type) {
        return prompt.messages().stream().flatMap(message -> message.content().stream()).filter(type::isInstance).map(type::cast).toList();
    }

    /**
     * 任务会话（system 里有任务的说明）：直接回结论。主会话：第一次放出任务；拿到“已开始”后说一句就结束；
     * 看到任务的结果后转述它。
     */
    private final ModelClient model = (prompt, chunks) -> {
        if (prompt.system().contains("## This is a task")) {
            taskPrompts.add(prompt);
            if (taskAsks && blocks(prompt, ToolResultBlock.class).isEmpty()) {
                return new ModelOutcome.Replied(List.of(new ToolCallBlock("q1", "ask_user", "{}",
                        new Json.Obj(Map.of("question", new Json.Str("May I delete build/?"))))), StopReason.TOOL_USE, "m", null);
            }
            return text("Module A is fine. Please delete build/.");
        }
        mainPrompts.add(prompt);
        List<TaskResultBlock> back = blocks(prompt, TaskResultBlock.class);
        if (!back.isEmpty()) {
            return text("The task reported: " + back.getLast().text());
        }
        if (blocks(prompt, ToolResultBlock.class).isEmpty()) {
            return new ModelOutcome.Replied(List.of(new ToolCallBlock("c1", TaskTools.TASK, "{}",
                    new Json.Obj(Map.of(TaskTools.PROMPT, new Json.Str("Check module A.\nReport what you find."))))), StopReason.TOOL_USE, "m", null);
        }
        return text("Started; I will tell you when it is back.");
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

    private String startedTask() {
        for (Prompt prompt : mainPrompts) {
            for (ToolResultBlock result : blocks(prompt, ToolResultBlock.class)) {
                Matcher matcher = STARTED.matcher(result.text());
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
        }
        throw new AssertionError("the main conversation never saw a started task: " + mainPrompts);
    }

    @Test
    void aTaskRunsInItsOwnSessionAndItsResultStartsARunNobodyAskedFor() throws Exception {
        assertEquals(200, server.post("alice", "/api/sessions/s1/submissions",
                "{\"requestId\":\"r1\",\"mode\":\"prompt\",\"text\":\"look into module A\"}").statusCode());

        // 第二轮没有人提问：是任务的结果开的
        JsonNode runs = waitForRuns("s1", 2);
        assertEquals("COMPLETED", runs.get(0).get("endReason").asString(), runs.toString());
        assertEquals("COMPLETED", runs.get(1).get("endReason").asString(), runs.toString());

        // 主会话有 task 工具；放出时当场拿到编号，那一轮说完就结束
        assertTrue(mainPrompts.getFirst().tools().stream().anyMatch(spec -> spec.name().equals(TaskTools.TASK)));
        String task = startedTask();
        String child = "s1-" + task;

        // 任务会话：只有交代的那句话，没有再放任务和写记忆的工具，system 里有任务的说明
        Prompt first = taskPrompts.getFirst();
        assertEquals(1, first.messages().size(), first.messages().toString());
        assertEquals("Check module A.\nReport what you find.", ((TextBlock) first.messages().getFirst().content().getFirst()).text());
        List<String> tools = first.tools().stream().map(spec -> spec.name()).toList();
        assertFalse(tools.contains(TaskTools.TASK), tools.toString());
        assertFalse(tools.contains(TaskTools.TASK_STOP), tools.toString());
        assertFalse(tools.contains("workspace_add"), tools.toString());
        assertTrue(tools.contains("ask_user"), tools.toString());

        // 结果作为任务的结果回到主会话，不是用户说的话；模型看到的文字写明了来源
        Prompt after = mainPrompts.getLast();
        List<TaskResultBlock> back = blocks(after, TaskResultBlock.class);
        assertEquals(1, back.size());
        assertEquals(task, back.getFirst().task());
        assertEquals("Module A is fine. Please delete build/.", back.getFirst().text());
        ContentBlock last = after.messages().getLast().content().getLast();
        assertTrue(last instanceof TaskResultBlock, last.toString());

        // 任务的会话看得到（按会话号），但不在会话清单里
        assertEquals("COMPLETED", waitForRuns(child, 1).get(0).get("endReason").asString());
        String listed = server.get("alice", "/api/sessions").body();
        assertTrue(listed.contains("\"s1\""), listed);
        assertFalse(listed.contains(child), listed);
    }

    private JsonNode tasks() throws Exception {
        return JSON.readTree(server.get("alice", "/api/sessions/s1/tasks").body()).get("tasks");
    }

    @Test
    void aTaskThatWaitsForTheUserIsListedAndCanBeStoppedAndItsSessionTakesNoPrompts() throws Exception {
        taskAsks = true;
        server.post("alice", "/api/sessions/s1/submissions", "{\"requestId\":\"r1\",\"mode\":\"prompt\",\"text\":\"look into module A\"}");
        waitForRuns("s1", 1);
        String task = startedTask();
        String child = "s1-" + task;

        // 任务停在等用户回答：主会话空闲，任务还在名单里
        JsonNode listed = null;
        for (int i = 0; i < 1500; i++) {
            listed = tasks();
            if (listed.size() == 1 && "AwaitingAnswer".equals(listed.get(0).get("runStatus").asString())) {
                break;
            }
            Thread.sleep(10);
        }
        assertEquals(1, listed.size(), listed.toString());
        assertEquals(task, listed.get(0).get("id").asString());
        assertEquals(child, listed.get(0).get("session").asString());
        assertTrue(listed.get(0).get("open").asBoolean(), listed.toString());
        assertEquals("Check module A.", listed.get(0).get("brief").asString());
        assertEquals("AwaitingAnswer", listed.get(0).get("runStatus").asString(), listed.toString());
        assertEquals(1, runs("s1").size());

        // 任务的会话不接受提问；别人的会话是 404
        assertEquals(409, server.post("alice", "/api/sessions/" + child + "/submissions",
                "{\"requestId\":\"r2\",\"mode\":\"prompt\",\"text\":\"hello?\"}").statusCode());
        assertEquals(404, server.post("bob", "/api/sessions/s1/tasks/stop", "{}").statusCode());
        assertEquals(404, server.get("bob", "/api/sessions/s1/tasks").statusCode());

        // 停掉：任务那一轮被撤销，主会话收到“已停止”，开一轮
        assertEquals(200, server.post("alice", "/api/sessions/s1/tasks/stop", "{\"task\":\"" + task + "\"}").statusCode());
        waitForRuns("s1", 2);
        assertEquals("CANCELLED", waitForRuns(child, 1).get(0).get("endReason").asString());
        List<TaskResultBlock> back = blocks(mainPrompts.getLast(), TaskResultBlock.class);
        assertEquals(tantalum.value.task.TaskOutcome.STOPPED, back.getFirst().outcome());
        JsonNode after = tasks();
        assertEquals(1, after.size(), after.toString());
        assertFalse(after.get(0).get("open").asBoolean(), after.toString());
        assertEquals("idle", after.get(0).get("runStatus").asString(), after.toString());

        // 再停一次：名单里已经没有它，什么都不发生
        assertEquals(200, server.post("alice", "/api/sessions/s1/tasks/stop", "{}").statusCode());
        Thread.sleep(200);
        assertEquals(2, runs("s1").size());
    }

    @Test
    void afterARestartNothingIsLeftOutstandingAndTheHistoryIsTheSame() throws Exception {
        server.post("alice", "/api/sessions/s1/submissions", "{\"requestId\":\"r1\",\"mode\":\"prompt\",\"text\":\"look into module A\"}");
        waitForRuns("s1", 2);
        String before = server.get("alice", "/api/sessions/s1/messages").body();
        int calls = mainPrompts.size() + taskPrompts.size();

        server.close();
        server = new TestServer(temp.resolve("data"), temp.resolve("ws"), model);

        assertEquals(2, runs("s1").size());
        assertEquals(before, server.get("alice", "/api/sessions/s1/messages").body());
        // 没有补发、没有重跑：重启后模型一次也没被调用
        Thread.sleep(200);
        assertEquals(calls, mainPrompts.size() + taskPrompts.size());
    }
}
