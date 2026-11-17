package tantalum.shell.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
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
import tantalum.value.json.Json;
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
 * 多用户下任务的隔离和归属（subagent 需求 2.3；服务器是多用户的）：任务的会话归放出它的那个人，别人看不到、停不了、
 * 答不了它的问题、撤销不了它的运行；两个人同时放任务，结果各回各的；任务会话那种样子的编号人占不了；
 * 管理员只读查看时看得到、动不了。
 */
class TaskIsolationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern STARTED = Pattern.compile("Started task (t\\d+-\\d+)");

    @TempDir
    Path temp;

    private TestServer server;
    /** 每一次给模型的请求，连同它是哪个会话的（看 system 里的任务说明和交代的内容区分）。 */
    private final List<Prompt> prompts = Collections.synchronizedList(new ArrayList<>());

    private static <T> List<T> blocks(Prompt prompt, Class<T> type) {
        return prompt.messages().stream().flatMap(message -> message.content().stream()).filter(type::isInstance).map(type::cast).toList();
    }

    private static String firstText(Prompt prompt) {
        return prompt.messages().getFirst().content().stream().filter(TextBlock.class::isInstance).map(TextBlock.class::cast)
                .map(TextBlock::text).findFirst().orElse("");
    }

    private static ModelOutcome text(String text) {
        return new ModelOutcome.Replied(List.of(new TextBlock(text)), StopReason.END_TURN, "m", null);
    }

    /**
     * 主对话：第一句话是 “delegate X” 就放一个交代为 “secret of X” 的任务。任务：先问用户一个问题（停在等回答），
     * 回答之后交回 “answer for X”。主对话看到结果后转述。
     */
    private final ModelClient model = (prompt, chunks) -> {
        prompts.add(prompt);
        if (prompt.system().contains("## This is a task")) {
            String who = firstText(prompt).replace("secret of ", "");
            if (blocks(prompt, ToolResultBlock.class).isEmpty()) {
                return new ModelOutcome.Replied(List.of(new ToolCallBlock("q1", "ask_user", "{}",
                        new Json.Obj(Map.of("question", new Json.Str("May " + who + "'s task go on?"))))), StopReason.TOOL_USE, "m", null);
            }
            return text("answer for " + who);
        }
        List<TaskResultBlock> back = blocks(prompt, TaskResultBlock.class);
        if (!back.isEmpty()) {
            return text("got: " + back.getLast().text());
        }
        if (blocks(prompt, ToolResultBlock.class).isEmpty() && firstText(prompt).startsWith("delegate ")) {
            return new ModelOutcome.Replied(List.of(new ToolCallBlock("c1", TaskTools.TASK, "{}", new Json.Obj(Map.of(TaskTools.PROMPT,
                    new Json.Str("secret of " + firstText(prompt).substring("delegate ".length())))))), StopReason.TOOL_USE, "m", null);
        }
        return text("ok");
    };

    @BeforeEach
    void start() throws Exception {
        Files.createDirectories(temp.resolve("ws"));
        server = new TestServer(temp.resolve("data"), temp.resolve("ws"), model);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private HttpResponse<String> send(String user, String method, String path, String body) throws Exception {
        HttpRequest.Builder request = server.request(user, path).header("content-type", "application/json");
        return server.http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode runs(String user, String session) throws Exception {
        return JSON.readTree(server.get(user, "/api/sessions/" + session + "/runs").body()).get("runs");
    }

    private void waitForRuns(String user, String session, int count) throws Exception {
        for (int i = 0; i < 1500; i++) {
            JsonNode runs = runs(user, session);
            if (runs != null && runs.size() >= count && runs.get(count - 1).has("endReason")) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("run " + count + " of " + session + " did not end: " + runs(user, session));
    }

    /** 这个人在这个会话里说 “delegate 名字”，等任务停在等回答；返回任务自己的会话编号。 */
    private String delegate(String user, String session) throws Exception {
        assertEquals(200, server.post(user, "/api/sessions/" + session + "/submissions",
                "{\"requestId\":\"r-" + session + "\",\"mode\":\"prompt\",\"text\":\"delegate " + user + "\"}").statusCode());
        waitForRuns(user, session, 1);
        JsonNode tasks = null;
        for (int i = 0; i < 1500; i++) {
            tasks = JSON.readTree(server.get(user, "/api/sessions/" + session + "/tasks").body()).get("tasks");
            if (tasks.size() == 1 && "AwaitingAnswer".equals(tasks.get(0).get("runStatus").asString())) {
                return tasks.get(0).get("session").asString();
            }
            Thread.sleep(10);
        }
        throw new AssertionError("the task of " + user + " did not reach its question: " + tasks);
    }

    private String questionOf(String user, String child) throws Exception {
        return JSON.readTree(server.get(user, "/api/sessions/" + child + "/questions").body()).get("questions").get(0).get("id").asString();
    }

    @Test
    void anotherUserCannotSeeStopAnswerOrCancelSomeoneElsesTask() throws Exception {
        String child = delegate("alice", "a1");
        String question = questionOf("alice", child);
        String childRun = runs("alice", child).get(0).get("run").asString();

        // bob：任务的会话读不到（对话、运行、问题、轨迹、任务清单），和不存在的一样
        for (String path : List.of("/messages", "/runs", "/trace", "/tasks", "/context")) {
            assertEquals(404, server.get("bob", "/api/sessions/" + child + path).statusCode(), path);
            assertEquals(404, server.get("bob", "/api/sessions/a1" + path).statusCode(), "a1" + path);
        }
        // “这个会话在等回答的问题”对别人是一张空表（这个接口对不存在的会话也回空表），问题本身看不到
        assertEquals("{\"questions\":[]}", server.get("bob", "/api/sessions/" + child + "/questions").body());
        assertTrue(server.get("alice", "/api/sessions/" + child + "/questions").body().contains("May alice's task go on?"));
        assertEquals(404, server.get("bob", "/api/runs/" + childRun).statusCode());
        // 停不了、答不了、撤销不了
        assertEquals(404, server.post("bob", "/api/sessions/a1/tasks/stop", "{}").statusCode());
        assertEquals(404, server.post("bob", "/api/sessions/" + child + "/questions/" + question, "{\"text\":\"yes\"}").statusCode());
        assertEquals(404, server.post("bob", "/api/runs/" + childRun + "/cancel", "{}").statusCode());
        // 也不能往里说话
        assertTrue(List.of(404, 409).contains(server.post("bob", "/api/sessions/" + child + "/submissions",
                "{\"requestId\":\"x1\",\"mode\":\"prompt\",\"text\":\"hello\"}").statusCode()));
        // bob 的清单和搜索里没有 alice 的东西
        String listed = server.get("bob", "/api/sessions").body();
        assertFalse(listed.contains("a1") || listed.contains(child), listed);
        assertFalse(server.get("bob", "/api/sessions/search?q=secret").body().contains(child));

        // 这一通下来 alice 的任务原样还在等她
        Thread.sleep(200);
        JsonNode tasks = JSON.readTree(server.get("alice", "/api/sessions/a1/tasks").body()).get("tasks");
        assertTrue(tasks.get(0).get("open").asBoolean(), tasks.toString());
        assertEquals("AwaitingAnswer", tasks.get(0).get("runStatus").asString());
        assertEquals(1, runs("alice", "a1").size());

        // 她自己回答，任务做完，结果回到她的对话
        assertEquals(200, server.post("alice", "/api/sessions/" + child + "/questions/" + question, "{\"text\":\"yes\"}").statusCode());
        waitForRuns("alice", "a1", 2);
        assertTrue(server.get("alice", "/api/sessions/a1/messages").body().contains("got: answer for alice"));
    }

    @Test
    void twoPeopleStartingTasksAtTheSameTimeEachGetTheirOwnResult() throws Exception {
        String aliceTask = delegate("alice", "a1");
        String bobTask = delegate("bob", "b1");
        assertFalse(aliceTask.equals(bobTask));

        // 任务的账在各自名下的目录里
        assertTrue(Files.exists(temp.resolve("data/sessions/alice/" + aliceTask + ".jsonl")));
        assertTrue(Files.exists(temp.resolve("data/sessions/bob/" + bobTask + ".jsonl")));
        assertFalse(Files.exists(temp.resolve("data/sessions/bob/" + aliceTask + ".jsonl")));
        assertFalse(Files.exists(temp.resolve("data/sessions/alice/" + bobTask + ".jsonl")));

        assertEquals(200, server.post("bob", "/api/sessions/" + bobTask + "/questions/" + questionOf("bob", bobTask), "{\"text\":\"yes\"}").statusCode());
        waitForRuns("bob", "b1", 2);
        // bob 的结果回来了，alice 的还在等：互不影响
        assertTrue(server.get("bob", "/api/sessions/b1/messages").body().contains("got: answer for bob"));
        assertEquals(1, runs("alice", "a1").size());
        assertTrue(JSON.readTree(server.get("alice", "/api/sessions/a1/tasks").body()).get("tasks").get(0).get("open").asBoolean());

        assertEquals(200, server.post("alice", "/api/sessions/" + aliceTask + "/questions/" + questionOf("alice", aliceTask), "{\"text\":\"yes\"}").statusCode());
        waitForRuns("alice", "a1", 2);
        String aliceSaw = server.get("alice", "/api/sessions/a1/messages").body();
        assertTrue(aliceSaw.contains("got: answer for alice"), aliceSaw);
        assertFalse(aliceSaw.contains("bob"), aliceSaw);
        assertFalse(server.get("bob", "/api/sessions/b1/messages").body().contains("alice"));

        // 模型那边：任务的请求里只有交代的那一句，没有另一个人的任何内容
        for (Prompt prompt : prompts) {
            String all = prompt.messages().toString();
            assertFalse(all.contains("alice") && all.contains("bob"), all);
        }
    }

    /** 任务会话那种样子的编号留给任务：人占不了，所以没法先占住一个编号让别人的任务送不进去。 */
    @Test
    void aSessionIdShapedLikeATasksCannotBeTakenByAPerson() throws Exception {
        for (String squatted : List.of("a1-t3-0", "a1-t4-0", "anything-t0-0")) {
            assertEquals(409, server.post("bob", "/api/sessions/" + squatted + "/submissions",
                    "{\"requestId\":\"s-" + squatted + "\",\"mode\":\"prompt\",\"text\":\"mine now\"}").statusCode(), squatted);
            assertEquals(409, server.post("alice", "/api/sessions/" + squatted + "/submissions",
                    "{\"requestId\":\"t-" + squatted + "\",\"mode\":\"prompt\",\"text\":\"mine now\"}").statusCode(), squatted);
        }
        // 上传附件也会认领新会话的编号：这条路同样占不了
        for (String squatted : List.of("a1-t3-0", "a1-t5-0")) {
            HttpResponse<String> upload = server.http.send(server.request("bob", "/api/sessions/" + squatted + "/attachments?name=a.txt")
                    .header("content-type", "application/octet-stream").POST(HttpRequest.BodyPublishers.ofString("mine")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(404, upload.statusCode(), upload.body());
        }
        assertFalse(server.get("bob", "/api/sessions").body().contains("-t3-0"));
        // 被拒的请求没有把编号占下：索引里没有这些会话
        for (String user : List.of("alice", "bob")) {
            assertEquals(404, server.get(user, "/api/sessions/a1-t3-0/messages").statusCode(), user);
        }

        // 之后 alice 的任务照常开始、照常回来
        String child = delegate("alice", "a1");
        Matcher id = STARTED.matcher(prompts.toString());
        assertTrue(id.find());
        assertEquals("a1-" + id.group(1), child);
        assertEquals(200, server.post("alice", "/api/sessions/" + child + "/questions/" + questionOf("alice", child), "{\"text\":\"yes\"}").statusCode());
        waitForRuns("alice", "a1", 2);
    }

    /** 管理员只读查看别人的对话（view-as）：对话里的任务卡片和任务自己的会话都看得到，但停不了、答不了。 */
    @Test
    void anAdministratorViewingSomeoneCanReadTheirTasksButNotTouchThem() throws Exception {
        server.users().add("root", TestServer.PASSWORD, true);
        String child = delegate("alice", "a1");
        String question = questionOf("alice", child);

        // 不带 viewAs：管理员和别人一样看不到
        assertEquals(404, send("root", "GET", "/api/sessions/" + child + "/messages", null).statusCode());

        String main = send("root", "GET", "/api/sessions/a1/messages?viewAs=alice", null).body();
        assertTrue(main.contains("\"kind\":\"task\"") && main.contains("\"taskSession\":\"" + child + "\""), main);
        HttpResponse<String> inside = send("root", "GET", "/api/sessions/" + child + "/messages?viewAs=alice", null);
        assertEquals(200, inside.statusCode());
        assertTrue(inside.body().contains("\"kind\":\"question\""), inside.body());

        // 写的一律不行
        assertTrue(send("root", "POST", "/api/sessions/a1/tasks/stop?viewAs=alice", "{}").statusCode() >= 400);
        assertTrue(send("root", "POST", "/api/sessions/" + child + "/questions/" + question + "?viewAs=alice", "{\"text\":\"yes\"}").statusCode() >= 400);
        assertEquals(404, send("root", "POST", "/api/sessions/a1/tasks/stop", "{}").statusCode());
        Thread.sleep(200);
        assertTrue(JSON.readTree(server.get("alice", "/api/sessions/a1/tasks").body()).get("tasks").get(0).get("open").asBoolean());
    }
}
