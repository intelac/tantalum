package tantalum.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.support.GenericApplicationContext;
import tantalum.kernel.hook.KernelHook;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.model.ClaudeClient;
import tantalum.shell.model.OverflowRules;
import tantalum.shell.store.SessionStore;
import tantalum.shell.web.TestServer;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.ModelFailed;
import tantalum.value.event.ModelReplied;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.ReasoningBlock;
import tantalum.value.model.ToolCallBlock;

/**
 * core-tools 非功能“真实模型”：三项，各有判定标准。会产生费用；同时设置 {@code ANTHROPIC_API_KEY} 和 {@code TANTALUM_SMOKE_CLAUDE}
 * 才运行。请求都开严格的思考校验（{@code prefix_mismatch_behavior: error}），历史一被改动就是 400，不会悄悄丢掉思考。
 */
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TANTALUM_SMOKE_CLAUDE", matches = ".+")
class CoreToolsClaudeTest {

    private static final String MODEL = "claude-sonnet-5-5";

    @TempDir
    Path temp;

    /** 备用模型打开：会话里出现工具变化后请求不该再带它。 */
    private static ClaudeClient strict(String effort) {
        return new ClaudeClient(AnthropicOkHttpClient.builder().apiKey(System.getenv("ANTHROPIC_API_KEY")).maxRetries(0).build(),
                new ClaudeClient.Settings(MODEL, 8192, effort, true, Duration.ofSeconds(300), Duration.ofSeconds(300), OverflowRules.defaults(),
                        true));
    }

    private static void turn(TestServer server, String user, String kind, String name, boolean enabled) throws Exception {
        HttpResponse<String> response = server.http.send(server.request(user, "/api/plugins/" + kind + "/" + name)
                .header("content-type", "application/json").PUT(HttpRequest.BodyPublishers.ofString("{\"enabled\":" + enabled + "}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
    }

    private static List<EventEnvelope> journal(Path data) throws Exception {
        return new SessionStore(data, EventCodec.withBuiltins()).readAll();
    }

    private static <T> List<T> events(List<EventEnvelope> journal, Class<T> type) {
        return journal.stream().map(EventEnvelope::event).filter(type::isInstance).map(type::cast).toList();
    }

    private static List<ToolCallBlock> calls(List<EventEnvelope> journal) {
        return events(journal, ModelReplied.class).stream().flatMap(reply -> reply.content().stream())
                .filter(ToolCallBlock.class::isInstance).map(ToolCallBlock.class::cast).toList();
    }

    private static long invalid(List<EventEnvelope> journal) {
        return events(journal, ModelFailed.class).stream().filter(failure -> failure.reason().name().equals("INVALID_REQUEST")).count();
    }

    private static void usage(String label, List<EventEnvelope> journal) {
        long input = 0;
        long output = 0;
        long cacheRead = 0;
        long cacheWrite = 0;
        long thinking = 0;
        List<ModelReplied> replies = events(journal, ModelReplied.class);
        for (ModelReplied reply : replies) {
            if (reply.usage() != null) {
                input += reply.usage().input();
                output += reply.usage().output();
                cacheRead += reply.usage().cacheRead();
                cacheWrite += reply.usage().cacheWrite();
            }
            thinking += reply.content().stream().filter(ReasoningBlock.class::isInstance).count();
        }
        System.out.println("[claude " + label + "] modelCalls=" + replies.size() + " input=" + input + " output=" + output
                + " cacheRead=" + cacheRead + " cacheWrite=" + cacheWrite + " thinkingBlocks=" + thinking);
    }

    /**
     * 需求 1：长文件只靠窗口读。6 万行的日志，暗号藏在第 41 237 行；告诉模型大概在哪一段。文件在测试电脑上（local-workspace），
     * 本机工作区的工具不能单独开关，所以用只读策略只放行 read_file，grep 调了也被拦下。
     * 判定：答出暗号；至少一次 read_file 带了 offset；没有一次 grep 真的执行；没有一次请求被拒。
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void aLongFileIsReadThroughAWindow() throws Exception {
        Path workspace = temp.resolve("ws");
        Path data = temp.resolve("data");
        Files.createDirectories(workspace.resolve("alice"));
        String log = IntStream.rangeClosed(1, 60_000).mapToObj(i -> i == 41_237 ? "line " + i + ": the vault code is OSPREY-4471"
                : "line " + i + ": routine heartbeat ok").collect(Collectors.joining("\n")) + "\n";
        Files.writeString(workspace.resolve("alice/big.log"), log);
        Consumer<GenericApplicationContext> onlyReadFile = context -> context.registerBean("readOnly", KernelHook.class,
                () -> new ReadOnlyHook(Set.of("read_file")));
        try (TestServer server = new TestServer(data, workspace, strict("low"), onlyReadFile, "--tantalum.hooks.required=readOnly")) {
            server.connectComputer("alice", workspace.resolve("alice"), false);
            String answer = CompactionScenario.ask(server, "alice", "s1",
                    "big.log has 60000 lines. One line between line 41000 and line 41500 contains a vault code. "
                            + "Find it with read_file and tell me the code.");
            List<EventEnvelope> journal = journal(data);
            usage("window", journal);
            System.out.println("[claude window] reads=" + calls(journal).stream().map(ToolCallBlock::rawArguments).toList() + " answer=" + answer);
            assertTrue(answer.contains("OSPREY-4471"), answer);
            assertTrue(calls(journal).stream().anyMatch(call -> call.name().equals("read_file") && call.rawArguments().contains("offset")));
            Set<String> greps = calls(journal).stream().filter(call -> call.name().equals("grep")).map(ToolCallBlock::id)
                    .collect(Collectors.toSet());
            assertTrue(events(journal, tantalum.value.event.ToolFinished.class).stream()
                    .filter(done -> greps.contains(done.toolCallId())).allMatch(tantalum.value.event.ToolFinished::error), "grep never ran");
            assertEquals(0, invalid(journal));
        }
    }

    /**
     * 需求 3：先读后改。一个算错了的函数和它的测试；模型读文件、用 edit_file 改函数。
     * 判定：测试脚本跑通；模型用的是 edit_file；改之前读过（先读后写没有拒绝过）。
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void aFunctionIsReadThenEdited() throws Exception {
        Path workspace = temp.resolve("ws");
        Path data = temp.resolve("data");
        Path user = Files.createDirectories(workspace.resolve("alice"));
        Files.writeString(user.resolve("pricing.py"), """
                def total(prices, discount):
                    \"\"\"Sum the prices, then take off the discount (a fraction between 0 and 1).\"\"\"
                    subtotal = 0
                    for price in prices:
                        subtotal += price
                    return subtotal * discount


                def count(prices):
                    return len(prices)
                """);
        Files.writeString(user.resolve("test_pricing.py"), """
                from pricing import total
                assert total([10, 20], 0.25) == 22.5, total([10, 20], 0.25)
                assert total([], 0.5) == 0
                print("ok")
                """);
        try (TestServer server = new TestServer(data, workspace, strict("low"))) {
            server.connectComputer("alice", user, true);
            String answer = CompactionScenario.ask(server, "alice", "s1",
                    "test_pricing.py fails because total() in pricing.py is wrong. Fix total() with edit_file. Do not change the test.");
            List<EventEnvelope> journal = journal(data);
            usage("edit", journal);
            Process test = new ProcessBuilder("python3", "test_pricing.py").directory(user.toFile()).redirectErrorStream(true).start();
            String output = new String(test.getInputStream().readAllBytes()).strip();
            System.out.println("[claude edit] test=" + output + " answer=" + answer.replace("\n", " | "));
            assertEquals(0, test.waitFor(), output);
            assertEquals("ok", output);
            assertTrue(calls(journal).stream().anyMatch(call -> call.name().equals("edit_file")));
            assertTrue(Files.readString(user.resolve("pricing.py")).contains("def count(prices):"), "the rest of the file is intact");
            assertEquals(0, invalid(journal));
        }
    }

    /**
     * 需求 8 和设计第 1 节：同一个会话，开始时 write_file 关着（local-workspace 起是插件页“改文件”子开关 {@code local/file-edit}）；带思考地答一个问题；中途打开；下一次提问模型用它写文件；再关掉，
     * 下一次提问不能再写。严格校验下历史里的思考一直有效。
     * 判定：第一问的回复里有思考块（否则这项测不到东西）；文件写出且内容对；关掉后没有新文件；没有一次请求被拒。
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void turningAToolOnAndOffMidSessionKeepsTheThinkingValid() throws Exception {
        Path workspace = temp.resolve("ws");
        Path data = temp.resolve("data");
        Path user = Files.createDirectories(workspace.resolve("alice"));
        try (TestServer server = new TestServer(data, workspace, strict("max"))) {
            server.connectComputer("alice", user, true);
            turn(server, "alice", "local", "file-edit", false);
            String first = CompactionScenario.ask(server, "alice", "s1",
                    "Let a1 = 7 and a(n+1) = 3*a(n) - 4. Find a closed form, prove it by induction, then compute a20 exactly and check it "
                            + "against the recurrence. Do not use any tools.");
            List<ContentBlock> firstReply = events(journal(data), ModelReplied.class).getFirst().content();
            assertTrue(firstReply.stream().anyMatch(ReasoningBlock.class::isInstance), "no thinking to protect: " + firstReply);

            turn(server, "alice", "local", "file-edit", true);
            String second = CompactionScenario.ask(server, "alice", "s1", "Now save just the value of a20 into result.txt with write_file.");
            System.out.println("[claude switch] second=" + second.replace("\n", " | "));

            turn(server, "alice", "local", "file-edit", false);
            String third = CompactionScenario.ask(server, "alice", "s1",
                    "Also save the value of a19 into second.txt if you can; if you have no tool for writing files, just say so.");

            List<EventEnvelope> journal = journal(data);
            usage("switch", journal);
            System.out.println("[claude switch] calls=" + calls(journal).stream().map(call -> call.name() + call.rawArguments()).toList()
                    + " results=" + events(journal, tantalum.value.event.ToolFinished.class).stream().map(done -> done.text()).toList()
                    + " failures=" + events(journal, ModelFailed.class).stream().map(failure -> failure.reason() + ":" + failure.detail()).toList()
                    + " assembled=" + events(journal, tantalum.value.event.ContextAssembled.class).stream()
                            .map(context -> String.valueOf(context.enabledTools())).toList());
            System.out.println("[claude switch] first=" + first.replace("\n", " | ") + " second=" + second.replace("\n", " | ")
                    + " third=" + third.replace("\n", " | "));
            assertEquals("5811307337", Files.readString(user.resolve("result.txt")).strip().replace(",", ""));
            assertFalse(Files.exists(user.resolve("second.txt")), "write_file was turned off again");
            assertEquals(0, invalid(journal), "strict thinking validation rejected a request");
        }
    }
    /**
     * hook-patch 需求 5：钩子的补丁以追加的 system 消息到模型，严格校验下历史里的思考一直有效。第 1 问钩子追加一句说明；第 2 问说明变了、
     * 还加了一个工具（服务器上没有这个工具，调用会得到“未知工具”的错误结果，这里只验证模型看见并调用了它）；第 3 问说明再变、工具撤回。
     * 判定：没有一次请求被拒；最后一次请求之前的回复里有思考块（否则最后那次请求没有要保护的签名）；第 2 问模型调用了钩子加的工具；
     * 第 3 问模型说没有那个工具。
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void hookPatchesArriveAsSystemMessagesAndKeepTheThinkingValid() throws Exception {
        Path workspace = temp.resolve("ws");
        Path data = temp.resolve("data");
        Files.createDirectories(workspace.resolve("alice"));
        tantalum.value.model.ToolSpec stamp = new tantalum.value.model.ToolSpec("stamp_answer",
                "Records the final numeric answer of this turn. Call it once with the answer before replying.",
                new tantalum.value.json.Json.Obj(java.util.Map.of("type", new tantalum.value.json.Json.Str("object"),
                        "properties", new tantalum.value.json.Json.Obj(java.util.Map.of("answer", new tantalum.value.json.Json.Obj(
                                java.util.Map.of("type", new tantalum.value.json.Json.Str("string"))))),
                        "required", new tantalum.value.json.Json.Arr(List.of(new tantalum.value.json.Json.Str("answer"))))));
        KernelHook byTurn = new KernelHook() {
            @Override public String name() { return "turnNotes"; }
            @Override public tantalum.value.hook.HookResult beforeModelCall(tantalum.value.state.KernelState state,
                    tantalum.value.hook.HookContext context) {
                long turn = state.sessions().get(context.run().session()).messages().stream()
                        .filter(message -> message.role() == tantalum.value.model.Role.USER
                                && message.content().stream().noneMatch(tantalum.value.model.ToolResultBlock.class::isInstance)).count();
                return switch ((int) turn) {
                    case 1 -> new tantalum.value.hook.Allow(new tantalum.value.hook.PromptPatch(
                            "House rule for this turn: answer in at most two sentences.", List.of()));
                    case 2 -> new tantalum.value.hook.Allow(new tantalum.value.hook.PromptPatch(
                            "House rule for this turn: you must call stamp_answer with the final number before you reply.", List.of(stamp)));
                    default -> new tantalum.value.hook.Allow(new tantalum.value.hook.PromptPatch(
                            "House rule for this turn: stamp_answer is gone; if asked about it, say it is no longer available.", List.of()));
                };
            }
        };
        Consumer<GenericApplicationContext> beans = context -> context.registerBean("turnNotes", KernelHook.class, () -> byTurn);
        try (TestServer server = new TestServer(data, workspace, strict("max"), beans)) {
            String first = CompactionScenario.ask(server, "alice", "s1",
                    "Let a1 = 5 and a(n+1) = 2*a(n) + 1. Find a closed form, prove it by induction, then compute a10 exactly and check it "
                            + "against the recurrence. Do not use any tools.");
            String second = CompactionScenario.ask(server, "alice", "s1", "Now a12, following the current house rule.");
            String third = CompactionScenario.ask(server, "alice", "s1", "Is stamp_answer still available? Then give me a13.");
            List<EventEnvelope> journal = journal(data);
            usage("hook-patch", journal);
            List<ModelReplied> replies = events(journal, ModelReplied.class);
            System.out.println("[claude hook-patch] calls=" + calls(journal).stream().map(call -> call.name() + call.rawArguments()).toList()
                    + " failures=" + events(journal, ModelFailed.class).stream().map(failure -> failure.reason() + ":" + failure.detail()).toList());
            System.out.println("[claude hook-patch] first=" + first.replace("\n", " | ") + " second=" + second.replace("\n", " | ")
                    + " third=" + third.replace("\n", " | "));
            assertEquals(0, invalid(journal), "strict thinking validation rejected a request");
            assertTrue(calls(journal).stream().anyMatch(call -> call.name().equals("stamp_answer")), "the hook tool was never called");
            assertTrue(replies.subList(0, replies.size() - 1).stream()
                    .anyMatch(reply -> reply.content().stream().anyMatch(ReasoningBlock.class::isInstance)), "no thinking to protect");
            assertTrue(second.contains("12287") || calls(journal).stream().anyMatch(call -> call.rawArguments().contains("12287")), second);
            assertTrue(third.toLowerCase().contains("no longer") || third.toLowerCase().contains("not available")
                    || third.toLowerCase().contains("gone") || third.toLowerCase().contains("unavailable"), third);
        }
    }
}
