package tantalum.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.kernel.hook.KernelHook;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.ModelOutcome;
import tantalum.shell.web.TestServer;
import tantalum.value.json.Json;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolCallBlock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * trace-rounds 需求 3.1：轨迹里“问了用户”的记录带上它是为哪一次工具调用问的，轨迹页据此把“等批准”并进工具那一行。
 * 工具审批插件在工具调用前问的，是它要拦的那个调用；{@code ask_user} 是它自己那次调用。会话文件不变，只是视图多一个字段。
 */
class TraceApprovalTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path temp;

    private static ToolCallBlock call(String id, String name, Map<String, Json> arguments) {
        return new ToolCallBlock(id, name, "{}", new Json.Obj(arguments));
    }

    private static HttpResponse<String> send(TestServer server, String method, String path, String body) throws Exception {
        return server.http.send(server.request("alice", path).header("content-type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static List<JsonNode> records(TestServer server, String kind) throws Exception {
        List<JsonNode> found = new ArrayList<>();
        for (JsonNode record : JSON.readTree(server.get("alice", "/api/sessions/s1/trace").body()).get("records")) {
            if (record.get("kind").asString().equals(kind)) {
                found.add(record);
            }
        }
        return found;
    }

    private static JsonNode awaitQuestion(TestServer server) throws Exception {
        for (int i = 0; i < 500; i++) {
            JsonNode questions = JSON.readTree(server.get("alice", "/api/sessions/s1/questions").body()).get("questions");
            if (!questions.isEmpty()) {
                return questions.get(0);
            }
            Thread.sleep(10);
        }
        throw new AssertionError("no question was asked");
    }

    private static void awaitEnd(TestServer server) throws Exception {
        for (int i = 0; i < 800; i++) {
            JsonNode runs = JSON.readTree(server.get("alice", "/api/sessions/s1/runs").body()).get("runs");
            if (runs.size() == 1 && runs.get(0).has("endReason")) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("the run did not end");
    }

    private static String toolRecord(TestServer server, String callId) throws Exception {
        return records(server, "tool").stream().filter(tool -> tool.get("call").asString().equals(callId)).findFirst().orElseThrow().get("id").asString();
    }

    @Test
    void aQuestionAskedBeforeAToolCallNamesThatCall() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        // 一次回复里两个调用：读记忆不用批准，写记忆要批准——问题要挂在写的那个上，不是第一个
        ModelClient model = (prompt, chunks) -> calls.getAndIncrement() == 0
                ? new ModelOutcome.Replied(List.of(call("c1", "memory_read", Map.of("name", new Json.Str("nothing"))),
                        call("c2", "memory_write", Map.of("name", new Json.Str("note"), "description", new Json.Str("d"),
                                "content", new Json.Str("remember this")))), StopReason.TOOL_USE, "m", null)
                : new ModelOutcome.Replied(List.of(new TextBlock("done")), StopReason.END_TURN, "m", null);
        try (TestServer server = new TestServer(temp.resolve("data"), temp.resolve("ws"), model,
                context -> context.registerBean("toolApproval", KernelHook.class, () -> new ToolApprovalHook(Set.of("memory_write"))))) {
            assertEquals(200, send(server, "PUT", "/api/plugins/plugin/toolApproval", "{\"enabled\":true}").statusCode());
            assertEquals(200, send(server, "POST", "/api/sessions/s1/submissions", "{\"requestId\":\"r1\",\"mode\":\"prompt\",\"text\":\"save it\"}").statusCode());
            JsonNode asked = awaitQuestion(server);
            String write = toolRecord(server, "c2");

            JsonNode waiting = records(server, "question").get(0);
            assertEquals("WAITING", waiting.get("status").asString());
            assertEquals(write, waiting.get("tool").asString(), "the question belongs to the call that needs approval: " + waiting);
            // 为这次调用而问的那条钩子决定指向同一个工具
            JsonNode decision = records(server, "hook").stream().filter(hook -> hook.get("result").asString().equals("ASK")).findFirst().orElseThrow();
            assertEquals(write, decision.get("tool").asString());

            assertEquals(200, send(server, "POST", "/api/sessions/s1/questions/" + asked.get("id").asString(),
                    "{\"choice\":\"" + ToolApprovalHook.ONCE + "\"}").statusCode());
            awaitEnd(server);
            JsonNode answered = records(server, "question").get(0);
            assertEquals("ANSWERED", answered.get("status").asString());
            assertEquals(write, answered.get("tool").asString(), "still there once it is answered");
            assertTrue(answered.has("end"));
            assertEquals(1, records(server, "question").size());
        }
    }

    @Test
    void anAskUserQuestionNamesItsOwnCall() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ModelClient model = (prompt, chunks) -> calls.getAndIncrement() == 0
                ? new ModelOutcome.Replied(List.of(call("a1", "ask_user", Map.of("question", new Json.Str("Delete the backups?"),
                        "choices", new Json.Arr(List.of(new Json.Str("delete"), new Json.Str("keep")))))), StopReason.TOOL_USE, "m", null)
                : new ModelOutcome.Replied(List.of(new TextBlock("ok")), StopReason.END_TURN, "m", null);
        try (TestServer server = new TestServer(temp.resolve("data"), temp.resolve("ws"), model)) {
            assertEquals(200, send(server, "POST", "/api/sessions/s1/submissions", "{\"requestId\":\"r1\",\"mode\":\"prompt\",\"text\":\"clean up\"}").statusCode());
            JsonNode asked = awaitQuestion(server);
            String own = toolRecord(server, "a1");
            assertEquals(own, records(server, "question").get(0).get("tool").asString());
            assertEquals(200, send(server, "POST", "/api/sessions/s1/questions/" + asked.get("id").asString(), "{\"choice\":\"keep\"}").statusCode());
            awaitEnd(server);
            assertEquals(own, records(server, "question").get(0).get("tool").asString());
        }
    }

    /** 不是为哪次工具调用问的（这里是模型调用前的钩子），没有这一项。 */
    @Test
    void aQuestionAskedElsewhereNamesNoCall() throws Exception {
        ModelClient model = (prompt, chunks) -> new ModelOutcome.Replied(List.of(new TextBlock("done")), StopReason.END_TURN, "m", null);
        try (TestServer server = new TestServer(temp.resolve("data"), temp.resolve("ws"), model,
                context -> context.registerBean("askFirst", KernelHook.class, AskFirst::new))) {
            assertEquals(200, send(server, "POST", "/api/sessions/s1/submissions", "{\"requestId\":\"r1\",\"mode\":\"prompt\",\"text\":\"hello\"}").statusCode());
            JsonNode asked = awaitQuestion(server);
            assertEquals(200, send(server, "POST", "/api/sessions/s1/questions/" + asked.get("id").asString(), "{\"choice\":\"go\"}").statusCode());
            awaitEnd(server);
            List<JsonNode> questions = records(server, "question");
            assertEquals(1, questions.size(), "the question is in the trace");
            assertEquals("ANSWERED", questions.get(0).get("status").asString());
            assertFalse(questions.get(0).has("tool"), questions.get(0).toString());
        }
    }

    /** 模型调用前问一次；答了就放行。 */
    static final class AskFirst implements KernelHook {
        private final java.util.concurrent.atomic.AtomicBoolean asked = new java.util.concurrent.atomic.AtomicBoolean();

        @Override public String name() { return "askFirst"; }

        @Override
        public tantalum.value.hook.HookResult beforeModelCall(tantalum.value.state.KernelState state, tantalum.value.hook.HookContext context) {
            if (asked.getAndSet(true)) {
                return tantalum.value.hook.Allow.PLAIN;
            }
            return new tantalum.value.hook.Ask(new tantalum.value.question.Question("first-" + context.run().run().value(),
                    tantalum.value.text.LocalText.of("Start?"), null,
                    List.of(new tantalum.value.question.Question.Choice("go", tantalum.value.text.LocalText.of("Go")))));
        }

        @Override
        public tantalum.value.hook.HookResult onAnswer(tantalum.value.state.KernelState state, tantalum.value.hook.HookContext context) {
            return tantalum.value.hook.Allow.PLAIN;
        }
    }
}
