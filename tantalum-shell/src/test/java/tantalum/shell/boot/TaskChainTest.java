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
import tantalum.value.model.ReportBlock;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolResultBlock;
import tantalum.value.assignment.AssignmentTools;
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
    /** 为真时主对话一次回复里放出两个任务。 */
    private volatile boolean twoTasks;
    /** 默认已经放行；要让任务先停着的测试换一个没放行的。 */
    private volatile java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(0);

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
            try {
                // 任务等放行后才交结论（测“放出它的会话被回收之后结果回来”）
                if (!release.await(30, java.util.concurrent.TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the task was never released");
                }
            } catch (InterruptedException stopping) {
                Thread.currentThread().interrupt();
            }
            if (taskAsks && blocks(prompt, ToolResultBlock.class).isEmpty()) {
                return new ModelOutcome.Replied(List.of(new ToolCallBlock("q1", "ask_user", "{}",
                        new Json.Obj(Map.of("question", new Json.Str("May I delete build/?"))))), StopReason.TOOL_USE, "m", null);
            }
            return text("Module A is fine. Please delete build/.");
        }
        mainPrompts.add(prompt);
        List<ReportBlock> back = blocks(prompt, ReportBlock.class);
        if (!back.isEmpty()) {
            return text("The task reported: " + back.getLast().text());
        }
        if (blocks(prompt, ToolResultBlock.class).isEmpty()) {
            List<ContentBlock> calls = new ArrayList<>();
            calls.add(new ToolCallBlock("c1", AssignmentTools.TASK, "{}",
                    new Json.Obj(Map.of(AssignmentTools.PROMPT, new Json.Str("Check module A.\nReport what you find.")))));
            if (twoTasks) {
                calls.add(new ToolCallBlock("c2", AssignmentTools.TASK, "{}", new Json.Obj(Map.of(AssignmentTools.PROMPT, new Json.Str("Check module B.")))));
            }
            return new ModelOutcome.Replied(calls, StopReason.TOOL_USE, "m", null);
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
                "{\"requestId\":\"r1\",\"delivery\":\"queue\",\"text\":\"look into module A\"}").statusCode());

        // 第二轮没有人提问：是任务的结果开的
        JsonNode runs = waitForRuns("s1", 2);
        assertEquals("COMPLETED", runs.get(0).get("endReason").asString(), runs.toString());
        assertEquals("COMPLETED", runs.get(1).get("endReason").asString(), runs.toString());

        // 主会话有 task 工具；放出时当场拿到编号，那一轮说完就结束
        assertTrue(mainPrompts.getFirst().tools().stream().anyMatch(spec -> spec.name().equals(AssignmentTools.TASK)));
        String task = startedTask();
        String child = "s1-" + task;

        // 任务会话：只有交代的那句话，没有再放任务和写记忆的工具，system 里有任务的说明
        Prompt first = taskPrompts.getFirst();
        assertEquals(1, first.messages().size(), first.messages().toString());
        // 交代进帮手会话时是它自己的内容块；发给模型的文字和以前一样
        assertEquals("Check module A.\nReport what you find.",
                ((tantalum.value.model.AssignmentBlock) first.messages().getFirst().content().getFirst()).text());
        List<String> tools = first.tools().stream().map(spec -> spec.name()).toList();
        assertFalse(tools.contains(AssignmentTools.TASK), tools.toString());
        assertFalse(tools.contains(AssignmentTools.TASK_STOP), tools.toString());
        assertFalse(tools.contains("workspace_add"), tools.toString());
        assertTrue(tools.contains("ask_user"), tools.toString());

        // 结果作为任务的结果回到主会话，不是用户说的话；模型看到的文字写明了来源
        Prompt after = mainPrompts.getLast();
        List<ReportBlock> back = blocks(after, ReportBlock.class);
        assertEquals(1, back.size());
        assertEquals(task, back.getFirst().assignment());
        assertEquals("Module A is fine. Please delete build/.", back.getFirst().text());
        ContentBlock last = after.messages().getLast().content().getLast();
        assertTrue(last instanceof ReportBlock, last.toString());

        // 任务的会话看得到（按会话号），但不在会话清单里
        assertEquals("COMPLETED", waitForRuns(child, 1).get(0).get("endReason").asString());
        // 主对话的条目里：任务是一张卡片（会话号是字符串，页面拿它去读任务的会话），结果是“任务结果”，都不是用户消息
        String mainMessages = server.get("alice", "/api/sessions/s1/messages").body();
        assertTrue(mainMessages.contains("\"kind\":\"task\"") && mainMessages.contains("\"taskSession\":\"" + child + "\""), mainMessages);
        assertTrue(mainMessages.contains("\"kind\":\"taskResult\"") && mainMessages.contains("\"outcome\":\"COMPLETED\""), mainMessages);
        assertEquals(1, mainMessages.split("\"kind\":\"user\"", -1).length - 1, mainMessages);
        // 任务会话的对话条目读得到（卡片展开时读它）：交代的那句话和它的回答
        String childMessages = server.get("alice", "/api/sessions/" + child + "/messages").body();
        assertTrue(childMessages.contains("Module A is fine. Please delete build/."), childMessages);
        // 轨迹（需求 10.5）：放任务的调用是完成的工具调用，带着任务的会话；结果开的那一轮写明起因，结果是一条“任务”记录；
        // 任务自己的轨迹里，交代的内容能指回放出它的会话
        JsonNode mainTrace = JSON.readTree(server.get("alice", "/api/sessions/s1/trace").body()).get("records");
        JsonNode call = find(mainTrace, "tool", "name", "task");
        assertEquals("DONE", call.get("status").asString(), call.toString());
        assertEquals(child, call.get("taskSession").asString(), call.toString());
        JsonNode result = find(mainTrace, "task", "side", "RESULT");
        assertEquals(child, result.get("session").asString(), result.toString());
        assertEquals("COMPLETED", result.get("outcome").asString(), result.toString());
        assertEquals("TASK_RESULT", find(mainTrace, "run", "run", result.get("run").asString()).get("trigger").asString());
        JsonNode childTrace = JSON.readTree(server.get("alice", "/api/sessions/" + child + "/trace").body()).get("records");
        assertEquals("s1", find(childTrace, "task", "side", "ASSIGNED").get("session").asString());
        assertEquals("TASK", find(childTrace, "run", "kind", "run").get("trigger").asString());

        String listed = server.get("alice", "/api/sessions").body();
        assertTrue(listed.contains("\"s1\""), listed);
        assertFalse(listed.contains(child), listed);
    }

    private static JsonNode find(JsonNode records, String kind, String field, String value) {
        for (JsonNode record : records) {
            if (kind.equals(record.get("kind").asString()) && record.has(field) && value.equals(record.get(field).asString())) {
                return record;
            }
        }
        throw new AssertionError("no " + kind + " with " + field + "=" + value + " in " + records);
    }

    private JsonNode tasks() throws Exception {
        return JSON.readTree(server.get("alice", "/api/sessions/s1/tasks").body()).get("tasks");
    }

    @Test
    void aTaskThatWaitsForTheUserIsListedAndCanBeStoppedAndItsSessionTakesNoPrompts() throws Exception {
        taskAsks = true;
        server.post("alice", "/api/sessions/s1/submissions", "{\"requestId\":\"r1\",\"delivery\":\"queue\",\"text\":\"look into module A\"}");
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
        // 卡片展开时读任务的会话：它调的工具、问的问题都在条目里
        String inside = server.get("alice", "/api/sessions/" + child + "/messages").body();
        assertTrue(inside.contains("\"kind\":\"tool\"") && inside.contains("\"kind\":\"question\""), inside);

        // 任务的会话不接受提问；别人的会话是 404
        assertEquals(409, server.post("alice", "/api/sessions/" + child + "/submissions",
                "{\"requestId\":\"r2\",\"delivery\":\"queue\",\"text\":\"hello?\"}").statusCode());
        assertEquals(404, server.post("bob", "/api/sessions/s1/tasks/stop", "{}").statusCode());
        assertEquals(404, server.get("bob", "/api/sessions/s1/tasks").statusCode());

        // 停掉：任务那一轮被撤销，主会话收到“已停止”，开一轮
        assertEquals(200, server.post("alice", "/api/sessions/s1/tasks/stop", "{\"task\":\"" + task + "\"}").statusCode());
        waitForRuns("s1", 2);
        assertEquals("CANCELLED", waitForRuns(child, 1).get(0).get("endReason").asString());
        List<ReportBlock> back = blocks(mainPrompts.getLast(), ReportBlock.class);
        assertEquals(tantalum.value.assignment.AssignmentOutcome.STOPPED, back.getFirst().outcome());
        JsonNode after = tasks();
        assertEquals(1, after.size(), after.toString());
        assertFalse(after.get(0).get("open").asBoolean(), after.toString());
        assertEquals("idle", after.get(0).get("runStatus").asString(), after.toString());

        // 再停一次：名单里已经没有它，什么都不发生
        assertEquals(200, server.post("alice", "/api/sessions/s1/tasks/stop", "{}").statusCode());
        Thread.sleep(200);
        assertEquals(2, runs("s1").size());
    }

    /** 放出任务的会话空闲久了会被回收出内存；结果回来时它从文件载回来，照常开一轮（subagent 设计“现有代码里已经确认的事实”）。 */
    @Test
    void aResultFindsTheSessionThatStartedItEvenAfterItLeftMemory() throws Exception {
        server.close();
        release = new java.util.concurrent.CountDownLatch(1);
        server = new TestServer(temp.resolve("data"), temp.resolve("ws"), model,
                "--tantalum.residency.idle=0s", "--tantalum.residency.max-resident=1000", "--tantalum.residency.sweep-interval=0s");
        Engine engine = server.context.getBean(Engine.class);
        server.post("alice", "/api/sessions/s1/submissions", "{\"requestId\":\"r1\",\"delivery\":\"queue\",\"text\":\"look into module A\"}");
        waitForRuns("s1", 1);
        tantalum.value.id.SessionId s1 = new tantalum.value.id.SessionId("s1");
        for (int i = 0; i < 1500 && engine.state().sessions().containsKey(s1); i++) {
            Thread.sleep(10);
        }
        assertFalse(engine.state().sessions().containsKey(s1), "the session that started the task left memory while the task was running");

        release.countDown();

        JsonNode runs = waitForRuns("s1", 2);
        assertEquals("COMPLETED", runs.get(1).get("endReason").asString(), runs.toString());
        assertEquals("Module A is fine. Please delete build/.", blocks(mainPrompts.getLast(), ReportBlock.class).getFirst().text());
        assertFalse(tasks().get(0).get("open").asBoolean());
    }

    private void restart(String... extra) {
        server.close();
        server = new TestServer(temp.resolve("data"), temp.resolve("ws"), model, extra);
    }

    private void say(String request, String text) throws Exception {
        assertEquals(200, server.post("alice", "/api/sessions/s1/submissions",
                "{\"requestId\":\"" + request + "\",\"delivery\":\"queue\",\"text\":\"" + text + "\"}").statusCode());
    }

    /** 两个会话的账重放出来和当时记下的结果一致（record-results）：任务的事件、命令、状态都在账里。 */
    @Test
    void theJournalsOfBothSessionsReplayToWhatWasRecorded() throws Exception {
        twoTasks = true;
        say("r1", "look into both modules");
        waitForRuns("s1", 2);
        for (int i = 0; i < 500 && tasks().toString().contains("\"open\":true"); i++) {
            Thread.sleep(10);
        }
        Thread.sleep(300);
        server.close();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int code = RecordCheckCommand.run(new String[] {"--tantalum.data-dir=" + temp.resolve("data"), "--tantalum.workspace=" + temp.resolve("ws")},
                new java.io.PrintStream(out, true, java.nio.charset.StandardCharsets.UTF_8),
                tantalum.shell.cli.CliText.of(tantalum.value.text.Language.EN));
        assertEquals(0, code, out.toString(java.nio.charset.StandardCharsets.UTF_8));
        server = new TestServer(temp.resolve("data"), temp.resolve("ws"), model);
    }

    /** 上限是配置项（subagent 需求 5）：同时在外面的任务数到了，多出来的那次调用得到说明原因的错误，别的照常。 */
    @Test
    void withALimitOfOneOpenTaskTheSecondCallIsRefusedWithAReason() throws Exception {
        restart("--tantalum.tasks.max-open=1");
        twoTasks = true;
        say("r1", "look into both modules");
        waitForRuns("s1", 2);
        List<ToolResultBlock> answers = blocks(mainPrompts.get(1), ToolResultBlock.class);
        assertEquals(2, answers.size());
        assertTrue(answers.get(0).text().startsWith("Started task"), answers.toString());
        assertTrue(answers.get(1).error() && answers.get(1).text().startsWith("Not started: 1 tasks are already running"), answers.toString());
        assertEquals(1, tasks().size());
    }

    /** 不让它自己接着做（max-unattended-runs=0）：结果记进对话，不开运行；人下一次说话时模型看到它。 */
    @Test
    void withNoUnattendedRunsAResultWaitsForThePersonToSpeak() throws Exception {
        restart("--tantalum.tasks.max-unattended-runs=0");
        say("r1", "look into module A");
        waitForRuns("s1", 1);
        for (int i = 0; i < 1500 && tasks().toString().contains("\"open\":true"); i++) {
            Thread.sleep(10);
        }
        assertFalse(tasks().get(0).get("open").asBoolean());
        Thread.sleep(300);
        assertEquals(1, runs("s1").size(), "no run was started by the result");
        String messages = server.get("alice", "/api/sessions/s1/messages").body();
        assertTrue(messages.contains("\"kind\":\"taskResult\"") && messages.contains("\"paused\":true"), messages);

        say("r2", "go on");
        waitForRuns("s1", 2);
        assertEquals("Module A is fine. Please delete build/.", blocks(mainPrompts.getLast(), ReportBlock.class).getFirst().text());
    }

    /** 管理员设了期限（tantalum.tasks.deadline）：到时任务按超时结束并被停掉，放出它的对话照常得到结果。 */
    @Test
    void withADeadlineATaskThatDoesNotEndIsTimedOutAndStopped() throws Exception {
        restart("--tantalum.tasks.deadline=1s");
        release = new java.util.concurrent.CountDownLatch(1);
        try {
            say("r1", "look into module A");
            JsonNode runs = waitForRuns("s1", 2);
            assertEquals("COMPLETED", runs.get(1).get("endReason").asString());
            ReportBlock result = blocks(mainPrompts.getLast(), ReportBlock.class).getFirst();
            assertEquals(tantalum.value.assignment.AssignmentOutcome.TIMED_OUT, result.outcome());
            String child = "s1-" + result.assignment();
            assertEquals("CANCELLED", waitForRuns(child, 1).get(0).get("endReason").asString());
            assertFalse(tasks().get(0).get("open").asBoolean());
        } finally {
            release.countDown();
        }
    }

    /**
     * 任务停在等用户回答时服务器重启：重启不打断等人的运行，任务还在名单里；回答之后它做完，结果照常回来。
     * 索引库删掉重建也一样（它是从账算出来的）：任务的会话仍然不在会话清单里。
     */
    @Test
    void aTaskWaitingForTheUserSurvivesARestartAndARebuiltIndex() throws Exception {
        taskAsks = true;
        say("r1", "look into module A");
        waitForRuns("s1", 1);
        String child = "s1-" + startedTask();
        for (int i = 0; i < 1500 && !tasks().toString().contains("AwaitingAnswer"); i++) {
            Thread.sleep(10);
        }
        assertTrue(tasks().toString().contains("AwaitingAnswer"), tasks().toString());

        server.close();
        try (java.util.stream.Stream<Path> files = java.nio.file.Files.walk(temp.resolve("data"))) {
            for (Path file : files.filter(path -> path.getFileName().toString().startsWith("index.db")).toList()) {
                java.nio.file.Files.delete(file);
            }
        }
        server = new TestServer(temp.resolve("data"), temp.resolve("ws"), model);

        JsonNode listed = tasks();
        assertEquals(1, listed.size(), listed.toString());
        assertTrue(listed.get(0).get("open").asBoolean(), listed.toString());
        assertEquals("AwaitingAnswer", listed.get(0).get("runStatus").asString(), listed.toString());
        String sessions = server.get("alice", "/api/sessions").body();
        assertTrue(sessions.contains("\"s1\"") && !sessions.contains(child), sessions);
        assertTrue(sessions.contains("\"openTasks\":1"), sessions);
        assertEquals(1, runs("s1").size(), "nothing was started by the restart");

        JsonNode question = JSON.readTree(server.get("alice", "/api/sessions/" + child + "/questions").body()).get("questions").get(0);
        assertEquals(200, server.post("alice", "/api/sessions/" + child + "/questions/" + question.get("id").asString(),
                "{\"text\":\"yes\"}").statusCode());
        JsonNode runs = waitForRuns("s1", 2);
        assertEquals("COMPLETED", runs.get(1).get("endReason").asString());
        assertEquals("Module A is fine. Please delete build/.", blocks(mainPrompts.getLast(), ReportBlock.class).getFirst().text());
        assertFalse(tasks().get(0).get("open").asBoolean());
    }

    @Test
    void afterARestartNothingIsLeftOutstandingAndTheHistoryIsTheSame() throws Exception {
        server.post("alice", "/api/sessions/s1/submissions", "{\"requestId\":\"r1\",\"delivery\":\"queue\",\"text\":\"look into module A\"}");
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
