package tantalum.examples;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.kernel.Kernel;
import tantalum.kernel.Limits;
import tantalum.kernel.Replay;
import tantalum.kernel.hook.HookChain;
import tantalum.kernel.step.BuiltinSteps;
import tantalum.kernel.table.TransitionTable;
import tantalum.shell.boot.Engine;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.journal.SessionPaths;
import tantalum.shell.loop.Intake;
import tantalum.shell.model.ChunkListener;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.ModelOutcome;
import tantalum.shell.route.RouteClients;
import tantalum.shell.store.SessionStore;
import tantalum.shell.web.TestServer;
import tantalum.value.command.CallModel;
import tantalum.value.event.EventEnvelope;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.ModelChangeBlock;
import tantalum.value.model.ModelChoice;
import tantalum.value.model.Prompt;
import tantalum.value.model.ReasoningBlock;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;

/**
 * model-picker 任务 11（需求 5.4、6.4、7.2–7.4）：两条脚本化路线（Claude 协议窗口 1M，OpenAI 协议窗口 30000），一个会话里换三次，
 * 换到小窗口那次先压缩、摘要由上一个模型写；system 和工具每次逐字节相同；回放出的 prompt 和实时发出的逐个相同；路线全部下线后
 * 会话照样打开；本 spec 之前的会话文件打开后逐字节不变，继续提问不追加换模型的说明。不访问外网。
 */
class ModelSwitchReplayTest {

    private static final Principal ALICE = new Principal("alice");
    private static final ModelChoice SONNET = new ModelChoice("claude", "claude-sonnet-5-5", "medium");
    private static final ModelChoice OPUS = new ModelChoice("claude", "claude-opus-5-5", "high");
    private static final ModelChoice QWEN = new ModelChoice("local", "qwen3.8", "on");
    /**
     * 20000 字节，按本地路线的系数约 11400 token：三轮之后超过 qwen 的压缩点 29000（窗口 30000 减预留 1000），一轮装得进摘要调用。
     * 窗口不用 8000：服务器的 system 和全部工具定义本身就有几千 token，摘要调用装不下任何一轮。
     */
    private static final String LONG = "cobalt ".repeat(2857);

    /** 两条路线共用一个调用记录，按实际先后。 */
    private record Call(String route, Prompt prompt) {}

    private static final class Scripted implements ModelClient {
        private final String route;
        private final List<Call> calls;
        private final Deque<ModelOutcome> script;

        Scripted(String route, List<Call> calls, List<ModelOutcome> script) {
            this.route = route;
            this.calls = calls;
            this.script = new ArrayDeque<>(script);
        }

        @Override
        public synchronized ModelOutcome call(Prompt prompt, ChunkListener chunks) {
            calls.add(new Call(route, prompt));
            ModelOutcome next = script.poll();
            if (next == null) {
                throw new AssertionError("route " + route + " was called once more than scripted");
            }
            return next;
        }
    }

    private static ModelOutcome thought(String signature, String text) {
        Map<String, Json> raw = new java.util.LinkedHashMap<>();
        raw.put("type", new Json.Str("thinking"));
        raw.put("thinking", new Json.Str("thinking " + signature));
        raw.put("signature", new Json.Str(signature));
        List<ContentBlock> content = List.of(new ReasoningBlock("claude", new Json.Obj(raw)), new TextBlock(text));
        return new ModelOutcome.Replied(content, StopReason.END_TURN, "scripted", null);
    }

    private static ModelOutcome answer(String text) {
        return new ModelOutcome.Replied(List.of(new TextBlock(text)), StopReason.END_TURN, "scripted", null);
    }

    @TempDir
    Path temp;

    private static String[] routes(boolean withLocal) {
        List<String> args = new ArrayList<>(List.of(
                "--tantalum.claude.models=claude-sonnet-5-5,claude-opus-5-5",
                "--tantalum.claude.context-window=1000000",
                "--tantalum.compaction.reserve-tokens=1000",
                "--tantalum.compaction.keep-recent-tokens=500",
                "--tantalum.compaction.summary-max-tokens=1000"));
        if (withLocal) {
            args.addAll(List.of(
                    "--tantalum.openai-routes.local.display-name=本地",
                    "--tantalum.openai-routes.local.base-url=http://127.0.0.1:1/v1",
                    "--tantalum.openai-routes.local.model=qwen3.8",
                    "--tantalum.openai-routes.local.thinking=chat-template",
                    "--tantalum.openai-routes.local.context-window=30000",
                    "--tantalum.openai-routes.local.max-tokens=1000"));
        }
        return args.toArray(String[]::new);
    }

    private static TestServer server(Path data, Path workspace, Map<String, ModelClient> clients, boolean withLocal) {
        ModelClient unused = (prompt, chunks) -> {
            throw new AssertionError("the single-client bean is not used when routes are injected");
        };
        return new TestServer(data, workspace, unused, context -> context.registerBean(RouteClients.class, () -> new RouteClients(clients)),
                routes(withLocal));
    }

    /** 提交一次提问（带选择或不带），等这个会话的第 {@code runs} 个运行结束。 */
    private static void ask(TestServer server, String session, String text, ModelChoice model, int runs) throws Exception {
        Intake.Submission submitted = server.context.getBean(Engine.class).intake().submit(ALICE, new SessionId(session),
                new RequestId("r-" + text.hashCode() + "-" + runs), "prompt", text, List.of(), model);
        assertEquals(200, submitted.status());
        String body = "";
        for (int i = 0; i < 1000 && count(body, "\"endReason\"") < runs; i++) {
            Thread.sleep(10);
            body = server.get(ALICE.value(), "/api/sessions/" + session + "/runs").body();
        }
        assertEquals(runs, count(body, "\"endReason\""), body);
        assertEquals(runs, count(body, "COMPLETED"), body);
    }

    private static int count(String text, String part) {
        int found = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) {
            found++;
        }
        return found;
    }

    private static boolean hasNotice(Prompt prompt) {
        return prompt.messages().stream().anyMatch(message -> message.content().stream().anyMatch(ModelChangeBlock.class::isInstance));
    }

    /** 这次提问后面紧跟着换模型的说明。 */
    private static boolean switchedHere(Prompt prompt) {
        return prompt.messages().getLast().content().stream().allMatch(ModelChangeBlock.class::isInstance);
    }

    @Test
    void switchingThreeTimesIsDeterministicAndReplaysWithoutTheRoutes() throws Exception {
        Path workspace = Files.createDirectories(temp.resolve("workspace/alice"));
        Path data = temp.resolve("data");
        List<Call> calls = Collections.synchronizedList(new ArrayList<>());
        Scripted claude = new Scripted("claude", calls, List.of(
                thought("SIG-1", LONG), thought("SIG-2", LONG), thought("SIG-3", LONG),
                answer("Goal: remember cobalt."),
                thought("SIG-5", "cobalt")));
        Scripted local = new Scripted("local", calls, List.of(answer("still cobalt")));

        String trace;
        try (TestServer server = server(data, workspace.getParent(), Map.of("claude", claude, "local", local), true)) {
            ask(server, "s1", "q1 remember cobalt", SONNET, 1);
            ask(server, "s1", "q2", OPUS, 2);
            ask(server, "s1", "q3", OPUS, 3);
            ask(server, "s1", "q4 switch to the small window", QWEN, 4);
            ask(server, "s1", "q5 back to Sonnet", SONNET, 5);
            trace = server.get(ALICE.value(), "/api/sessions/s1/trace").body();
        }

        // 每次调用到了对的路线、模型和档位；换到小窗口之前的摘要由上一个模型（Opus）写
        assertEquals(List.of("claude", "claude", "claude", "claude", "local", "claude"), calls.stream().map(Call::route).toList());
        assertEquals(List.of(SONNET, OPUS, OPUS, OPUS, QWEN, SONNET), calls.stream().map(call -> call.prompt().model()).toList());
        Prompt summary = calls.get(3).prompt();
        assertFalse(summary.output().toolsAllowed(), "the fourth call is the summary");
        // 需求 6.4：system 和工具定义每次逐字节相同，摘要调用也一样
        for (Call call : calls) {
            assertEquals(calls.getFirst().prompt().system(), call.prompt().system());
            assertEquals(calls.getFirst().prompt().tools(), call.prompt().tools());
        }
        // 换路线或模型的那次提问后面紧跟着说明；同一个模型再问不追加；摘要调用最后是摘要指令
        assertEquals(List.of(false, true, false, false, true, true), calls.stream().map(call -> switchedHere(call.prompt())).toList());
        assertTrue(calls.get(5).prompt().messages().stream().anyMatch(message -> message.content()
                .contains(new ModelChangeBlock(QWEN.label(), SONNET.label()))));

        // 需求 7.2：只用 journal 回放，prompt 和实时发出的逐个相同（回放不经过路线）
        List<EventEnvelope> journal = new SessionStore(data, EventCodec.withBuiltins()).readAll();
        TransitionTable table = new TransitionTable();
        BuiltinSteps.register(table);
        Replay.Result replay = Replay.run(new Kernel(table, new HookChain(List.of()), (tool, args) -> false, Limits.DEFAULT), journal);
        List<Prompt> replayed = replay.commands().stream().filter(CallModel.class::isInstance)
                .map(command -> ((CallModel) command).prompt()).toList();
        assertEquals(calls.stream().map(Call::prompt).toList(), replayed);

        // 需求 7.3：本地路线从配置里删掉、Claude 一调就失败，会话和轨迹照样打开，内容一样
        ModelClient down = (prompt, chunks) -> {
            throw new AssertionError("opening a session must not call a model");
        };
        try (TestServer offline = server(data, workspace.getParent(), Map.of("claude", down), false)) {
            assertEquals(200, offline.get(ALICE.value(), "/api/sessions/s1/messages").statusCode());
            assertEquals(trace, offline.get(ALICE.value(), "/api/sessions/s1/trace").body());
        }
    }

    /** 需求 7.4：本 spec 之前的会话（带思考块）打开后文件不变；继续提问用默认模型，不追加说明，旧的思考块照样发回 Claude。 */
    @Test
    void aSessionFromBeforeThisSpecOpensUnchangedAndContinuesWithoutANotice() throws Exception {
        Path workspace = Files.createDirectories(temp.resolve("workspace/alice"));
        Path data = temp.resolve("data");
        Path file = new SessionPaths(data).sessionFile(ALICE, new SessionId("s-old"));
        Files.createDirectories(file.getParent());
        byte[] original;
        try (InputStream in = getClass().getResourceAsStream("/model-picker/pre-model-picker.jsonl")) {
            original = in.readAllBytes();
        }
        Files.write(file, original);
        List<Call> calls = Collections.synchronizedList(new ArrayList<>());
        Scripted claude = new Scripted("claude", calls, List.of(answer("cobalt")));
        try (TestServer server = server(data, workspace.getParent(), Map.of("claude", claude), true)) {
            String messages = server.get(ALICE.value(), "/api/sessions/s-old/messages").body();
            assertTrue(messages.contains("I will remember cobalt."), messages);
            assertArrayEquals(original, Files.readAllBytes(file), "opening an old session writes nothing");

            ask(server, "s-old", "What was the word?", null, 2);
        }
        assertEquals(1, calls.size());
        Prompt prompt = calls.getFirst().prompt();
        assertEquals(SONNET, prompt.model(), "no choice anywhere: the administrator's default");
        assertFalse(hasNotice(prompt), "the first recorded choice of an old session is not a switch");
        assertEquals("claude", ((ReasoningBlock) prompt.messages().get(1).content().getFirst()).provider());
        assertEquals(List.of(new TextBlock("What was the word?")), prompt.messages().getLast().content());
        byte[] after = Files.readAllBytes(file);
        assertArrayEquals(original, java.util.Arrays.copyOf(after, original.length), "only appended");
        assertTrue(after.length > original.length);
    }
}
