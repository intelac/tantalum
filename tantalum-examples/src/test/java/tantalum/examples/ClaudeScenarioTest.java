package tantalum.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.support.GenericApplicationContext;
import tantalum.kernel.hook.KernelHook;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.journal.Journal;
import tantalum.shell.model.ClaudeClient;
import tantalum.shell.model.OverflowRules;
import tantalum.shell.plugin.AfterToolHook;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.ToolOutput;
import tantalum.shell.plugin.WorkType;
import tantalum.shell.web.TestServer;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.ModelFailed;
import tantalum.value.event.ModelInterrupted;
import tantalum.value.event.ModelReplied;
import tantalum.value.event.ToolFinished;
import tantalum.value.event.WorkDone;
import tantalum.value.hook.Allow;
import tantalum.value.hook.HookContext;
import tantalum.value.hook.HookResult;
import tantalum.value.json.Json;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.FailureReason;
import tantalum.value.model.ReasoningBlock;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.state.KernelState;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 用真实的 Claude 跑完整的环，每个场景对应一条需求或一个只有真实模型才暴露的风险。
 * 会产生费用；同时设置 {@code ANTHROPIC_API_KEY} 和 {@code TANTALUM_SMOKE_CLAUDE} 才运行。每个场景结束时打印用量。
 */
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TANTALUM_SMOKE_CLAUDE", matches = ".+")
class ClaudeScenarioTest {

    private static final String MODEL = "claude-sonnet-5-5";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path temp;

    private static ClaudeClient claude(String model, String effort) {
        return new ClaudeClient(AnthropicOkHttpClient.builder().apiKey(System.getenv("ANTHROPIC_API_KEY")).maxRetries(0).build(),
                model, 8192, effort, true, Duration.ofSeconds(300), Duration.ofSeconds(300), OverflowRules.defaults());
    }

    /** 记录每个钩子位置被调用的次数和看到的上下文，结果一律放行。 */
    static final class RecordingHook implements KernelHook {
        final Map<String, List<String>> seen = new ConcurrentHashMap<>();

        @Override public String name() { return "recording"; }

        private HookResult note(String point, String detail) {
            seen.computeIfAbsent(point, key -> Collections.synchronizedList(new ArrayList<>())).add(detail);
            return Allow.PLAIN;
        }

        @Override public HookResult beforeModelCall(KernelState state, HookContext context) { return note("beforeModelCall", ""); }
        @Override public HookResult beforeToolCall(KernelState state, HookContext context) { return note("beforeToolCall", context.call().name()); }
        @Override public HookResult onFailure(KernelState state, HookContext context) { return note("onFailure", context.failure().reason().name()); }
        @Override public HookResult beforeRunEnd(KernelState state, HookContext context) { return note("beforeRunEnd", context.work() == null ? "first" : "afterWork"); }
        @Override public HookResult onWorkResult(KernelState state, HookContext context) { return note("onWorkResult", context.work().workType()); }

        int count(String point) {
            return seen.getOrDefault(point, List.of()).size();
        }
    }

    private static Map<String, List<String>> snapshot(RecordingHook hook) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        hook.seen.forEach((point, calls) -> {
            synchronized (calls) {
                copy.put(point, List.copyOf(calls));
            }
        });
        return copy;
    }

    /** 一个会写文件的工具：只读策略应当拦住它，所以它一旦真的执行就是失败。 */
    private static Tool writeFile(AtomicInteger executions) {
        Map<String, Json> properties = new LinkedHashMap<>();
        properties.put("path", new Json.Obj(Map.of("type", new Json.Str("string"))));
        properties.put("content", new Json.Obj(Map.of("type", new Json.Str("string"))));
        Json schema = new Json.Obj(Map.of("type", new Json.Str("object"), "properties", new Json.Obj(properties)));
        return new Tool() {
            @Override public String name() { return "write_file"; }
            @Override public String description() { return "Write content to a file in the workspace. path is relative to the workspace."; }
            @Override public Json schema() { return schema; }
            @Override public ToolOutput run(ToolContext context, Json arguments) {
                executions.incrementAndGet();
                return ToolOutput.ok("written");
            }
        };
    }

    private Path workspace(String user) throws Exception {
        Path workspace = temp.resolve("workspace");
        Files.createDirectories(workspace.resolve(user));
        return workspace;
    }

    private static String submit(TestServer server, String user, String session, String text) throws Exception {
        HttpResponse<String> response = server.post(user, "/api/sessions/" + session + "/submissions",
                "{\"requestId\":\"" + java.util.UUID.randomUUID() + "\",\"mode\":\"prompt\",\"text\":" + JSON.writeValueAsString(text) + "}");
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }

    /** 等到该会话最新的运行结束，返回它的视图。 */
    private static JsonNode waitForLatestRun(TestServer server, String user, String session, Duration limit) throws Exception {
        Instant deadline = Instant.now().plus(limit);
        JsonNode latest = null;
        while (Instant.now().isBefore(deadline)) {
            JsonNode runs = JSON.readTree(server.get(user, "/api/sessions/" + session + "/runs").body()).get("runs");
            if (runs != null && !runs.isEmpty()) {
                latest = runs.get(runs.size() - 1);
                if (latest.has("endReason")) {
                    return latest;
                }
            }
            Thread.sleep(100);
        }
        throw new AssertionError("run did not end in " + limit + ": " + latest);
    }

    private static List<EventEnvelope> journal(Path data) throws Exception {
        try (Journal journal = Journal.open(data, EventCodec.withBuiltins())) {
            return journal.readAll();
        }
    }

    private static <T> List<T> events(List<EventEnvelope> journal, Class<T> type) {
        return journal.stream().map(EventEnvelope::event).filter(type::isInstance).map(type::cast).toList();
    }

    private static String lastText(List<EventEnvelope> journal) {
        List<ModelReplied> replies = events(journal, ModelReplied.class);
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : replies.getLast().content()) {
            if (block instanceof TextBlock plain) {
                text.append(plain.text());
            }
        }
        return text.toString();
    }

    private static String usage(String label, List<EventEnvelope> journal) {
        List<ModelReplied> replies = events(journal, ModelReplied.class);
        long input = 0;
        long output = 0;
        long cacheRead = 0;
        long cacheWrite = 0;
        long thinking = 0;
        for (ModelReplied reply : replies) {
            if (reply.usage() != null) {
                input += reply.usage().input();
                output += reply.usage().output();
                cacheRead += reply.usage().cacheRead();
                cacheWrite += reply.usage().cacheWrite();
            }
            thinking += reply.content().stream().filter(ReasoningBlock.class::isInstance).count();
        }
        String line = "[claude " + label + "] modelCalls=" + replies.size() + " input=" + input + " output=" + output
                + " cacheRead=" + cacheRead + " cacheWrite=" + cacheWrite + " thinkingBlocks=" + thinking;
        System.out.println(line);
        return line;
    }

    /**
     * 需求 4.4、4.5（一次运行里多轮工具）和长任务：八个文件串成一条链，每一步都依赖上一步的结果，
     * 模型只能顺序调八次 read_file。判定：运行完成、至少八次工具结果、找到秘密词。
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void longTaskFollowsAnEightStepChainOfSequentialToolCalls() throws Exception {
        Path workspace = workspace("chain");
        for (int i = 1; i < 8; i++) {
            Files.writeString(workspace.resolve("chain/step" + i + ".txt"), "Not here. Read step" + (i + 1) + ".txt next.\n");
        }
        Files.writeString(workspace.resolve("chain/step8.txt"), "The secret word is ZEBRA-8842.\n");
        Path data = temp.resolve("data-chain");

        try (TestServer server = new TestServer(data, workspace, claude(MODEL, "medium"))) {
            submit(server, "chain", "s1", "Start by reading step1.txt with read_file and follow the instruction in each file "
                    + "until you find the secret word. Each file tells you the next file. Report the secret word at the end.");
            JsonNode run = waitForLatestRun(server, "chain", "s1", Duration.ofMinutes(8));
            assertEquals("COMPLETED", run.get("endReason").asString(), run.toString());
            JsonNode turns = JSON.readTree(server.get("chain", "/api/runs/" + run.get("run").asString() + "/turns").body()).get("turns");
            assertTrue(turns.size() >= 9, "expected at least nine model calls, got " + turns.size());
        }

        List<EventEnvelope> journal = journal(data);
        List<ToolFinished> tools = events(journal, ToolFinished.class);
        assertTrue(tools.size() >= 8, "expected eight sequential read_file results, got " + tools.size());
        assertTrue(lastText(journal).contains("ZEBRA-8842"), lastText(journal));
        usage("long task", journal);
    }

    /**
     * tech.md 决定 11：带签名的 thinking 块经 journal 存取后原样回传，历史只追加。现有冒烟只验证了一轮工具；
     * 这里让每一步都要算一道题才知道下一个文件，用 high effort 诱发 thinking，验证签名跨多轮工具仍被接受。
     * 模型不产生 thinking 时无法验证，测试按“跳过”处理而不是假通过。
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void thinkingSignaturesStayValidAcrossSeveralToolTurns() throws Exception {
        Path workspace = workspace("think");
        for (int i = 1; i < 5; i++) {
            Files.writeString(workspace.resolve("think/step" + i + ".txt"),
                    "Not here. Let a1 = 3 and a(n+1) = 2·a(n) − 1. Compute N = a4 − 16 + " + i
                            + ", double-check it, and read stepN.txt next. A wrong N leads to a file that does not exist.\n");
        }
        Files.writeString(workspace.resolve("think/step5.txt"), "The secret word is OSPREY-2217.\n");
        Path data = temp.resolve("data-think");

        try (TestServer server = new TestServer(data, workspace, claude(MODEL, "high"))) {
            submit(server, "think", "s1", "Read step1.txt with read_file. Each file gives a recurrence puzzle whose answer is the "
                    + "number of the next file. Think it through carefully and verify your arithmetic before every read, because a wrong "
                    + "answer points at a file that does not exist. Report the secret word at the end.");
            JsonNode run = waitForLatestRun(server, "think", "s1", Duration.ofMinutes(8));
            assertEquals("COMPLETED", run.get("endReason").asString(), run.toString());
        }

        List<EventEnvelope> journal = journal(data);
        List<ModelReplied> replies = events(journal, ModelReplied.class);
        assertTrue(replies.size() >= 6, "expected five sequential reads plus the answer, got " + replies.size());
        assertTrue(lastText(journal).contains("OSPREY-2217"), lastText(journal));
        long turnsWithThinking = replies.stream()
                .filter(reply -> reply.content().stream().anyMatch(ReasoningBlock.class::isInstance)).count();
        usage("thinking chain", journal);
        org.junit.jupiter.api.Assumptions.assumeTrue(turnsWithThinking >= 2,
                "the model produced thinking in " + turnsWithThinking + " turns, so the multi-turn signature round trip was not exercised");
    }

    /**
     * 多轮插件，每一个钩子位置：beforeToolCall（只读策略拦下 write_file）、处理器钩子（截断大文件）、
     * beforeRunEnd 要求核对引用（NeedWork）、onWorkResult 看到核对结果，beforeModelCall 每次都过。
     * onFailure 在 {@link #failureHookSeesARealApiError} 里单独覆盖。判定：每个位置都被调用，写文件没有真的执行，
     * 模型看到了拦截和截断的结果，引用核对发生过，最终引用正确。
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void everyHookPointFiresAcrossSeveralToolRoundsInOneRun() throws Exception {
        Path workspace = workspace("hooks");
        StringBuilder big = new StringBuilder();
        for (int i = 1; i <= 200; i++) {
            big.append("line ").append(i).append(": the quick brown fox jumps over the lazy dog\n");
        }
        Files.writeString(workspace.resolve("hooks/index.txt"), "The large log is in big.txt. The diary is in notes.txt.\n");
        Files.writeString(workspace.resolve("hooks/big.txt"), big.toString());
        Files.writeString(workspace.resolve("hooks/notes.txt"), "Monday: rain\nTuesday: wind\nWednesday: ZEBRA sighting at the river\nThursday: sun\nFriday: fog\n");
        Path data = temp.resolve("data-hooks");
        RecordingHook recording = new RecordingHook();
        AtomicInteger writes = new AtomicInteger();
        // 记录钩子注册在最前面：链在第一个 Stop / NeedWork 处短路，否则它看不到被拦下的调用。
        Consumer<GenericApplicationContext> plugins = context -> {
            context.registerBean("writeFile", Tool.class, () -> writeFile(writes));
            context.registerBean("recording", KernelHook.class, () -> recording);
            context.registerBean("readOnly", KernelHook.class, () -> new ReadOnlyHook(Set.of("read_file", "grep")));
            context.registerBean("citationCheck", KernelHook.class, CitationCheckHook::new);
            context.registerBean("citationWork", WorkType.class, CitationWork::new);
            context.registerBean("truncate", AfterToolHook.class, () -> new TruncateOutputHook(1500));
        };

        String runId;
        Map<String, List<String>> live;
        List<String> turnBodies = new ArrayList<>();
        try (TestServer server = new TestServer(data, workspace, claude(MODEL, "medium"), plugins)) {
            // 每一步都依赖上一步的结果，所以模型不能把它们并成一轮：先读 index 才知道文件名，写 summary 要用找到的行号。
            submit(server, "hooks", "s1", "Step 1: read index.txt with read_file to learn the file names; you do not know them yet. "
                    + "Step 2: read the large log file it names and tell me whether the content you received was cut short; "
                    + "also read the diary file it names and find the line number that mentions ZEBRA. "
                    + "Step 3: call write_file to save that line number into summary.txt and tell me whether that worked. "
                    + "Finally cite the ZEBRA line exactly in the form notes.txt:LINE. Answer in Chinese.");
            JsonNode run = waitForLatestRun(server, "hooks", "s1", Duration.ofMinutes(8));
            assertEquals("COMPLETED", run.get("endReason").asString(), run.toString());
            runId = run.get("run").asString();
            // 下面的回放接口会让内核重跑同一段 journal，纯钩子也会被再次调用；先把实时运行里的记录拷下来。
            live = snapshot(recording);
            JsonNode turns = JSON.readTree(server.get("hooks", "/api/runs/" + runId + "/turns").body()).get("turns");
            for (JsonNode turn : turns) {
                turnBodies.add(server.get("hooks", "/api/runs/" + runId + "/turns/" + turn.get("number").asInt()).body());
            }
        }

        List<EventEnvelope> journal = journal(data);
        List<ModelReplied> replies = events(journal, ModelReplied.class);
        assertTrue(replies.stream().filter(reply -> reply.stop() == StopReason.TOOL_USE).count() >= 2,
                "expected several tool rounds in one run");

        assertEquals(0, writes.get(), "the read-only hook must stop write_file before it runs");
        assertTrue(replies.stream().flatMap(reply -> reply.content().stream()).anyMatch(block ->
                block instanceof ToolCallBlock call && call.name().equals("write_file")), "the model never tried write_file");
        assertTrue(turnBodies.stream().anyMatch(body -> body.contains("read-only policy blocks write_file")),
                "the model should have received the blocked result in a later prompt");

        assertTrue(events(journal, ToolFinished.class).stream().anyMatch(tool -> tool.text().contains("more characters truncated")),
                "TruncateOutputHook should have cut big.txt");

        List<WorkDone> work = events(journal, WorkDone.class);
        assertFalse(work.isEmpty(), "beforeRunEnd should have requested citation work");
        assertTrue(work.stream().allMatch(done -> done.workType().equals(CitationWork.TYPE)));
        assertTrue(lastText(journal).contains("notes.txt:3"), lastText(journal));

        assertEquals(replies.size() + events(journal, ModelFailed.class).size(), live.get("beforeModelCall").size(), live.toString());
        assertTrue(live.getOrDefault("beforeToolCall", List.of()).containsAll(List.of("read_file", "write_file")), live.toString());
        assertTrue(live.getOrDefault("beforeRunEnd", List.of()).containsAll(List.of("first", "afterWork")),
                "first call asks for work, the next one sees the work result: " + live);
        assertEquals(work.size(), live.getOrDefault("onWorkResult", List.of()).size(), live.toString());
        // 回放接口把同一段 journal 又过了一遍内核，纯钩子被再次调用：这正是钩子不能有副作用的原因。
        assertTrue(recording.count("beforeModelCall") > live.get("beforeModelCall").size(),
                "replaying turns should have invoked the hooks again");

        System.out.println("[claude hooks] liveHookCalls=" + live + " correctionRounds="
                + work.stream().filter(done -> done.continueText() != null).count()
                + " hookCallsIncludingReplay=" + recording.count("beforeModelCall"));
        usage("hooks", journal);
    }

    /** beforeModelCall 的 Stop 路径：每日额度用完后，下一次模型调用前运行被钩子停止，而不是继续花钱。 */
    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void quotaHookStopsTheRunBeforeTheSecondModelCall() throws Exception {
        Path workspace = workspace("quota");
        Files.writeString(workspace.resolve("quota/note.txt"), "The secret word is PELICAN-3301.\n");
        Path data = temp.resolve("data-quota");
        RecordingHook recording = new RecordingHook();
        // 钩子链按注册顺序遍历，遇到 Stop 就短路：记录钩子注册在前，才能看到被额度钩子拦下的那次调用。
        Consumer<GenericApplicationContext> plugins = context -> {
            context.registerBean("recording", KernelHook.class, () -> recording);
            context.registerBean("quota", KernelHook.class, () -> new DailyQuotaHook(1));
        };

        try (TestServer server = new TestServer(data, workspace, claude(MODEL, "medium"), plugins)) {
            submit(server, "quota", "s1", "Use read_file to read note.txt, then tell me the secret word.");
            JsonNode run = waitForLatestRun(server, "quota", "s1", Duration.ofMinutes(4));
            assertEquals("STOPPED", run.get("endReason").asString(), run.toString());
            assertTrue(run.get("detail").asString().contains("daily token quota"), run.toString());
        }

        List<EventEnvelope> journal = journal(data);
        assertEquals(1, events(journal, ModelReplied.class).size(), "only the first call may happen");
        assertEquals(2, recording.count("beforeModelCall"), recording.seen.toString());
        usage("quota", journal);
    }

    /** onFailure：一个真实的 API 错误（模型名不存在）进入失败路径，钩子看到失败原因，运行以 FAILED 结束，不重试。 */
    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void failureHookSeesARealApiError() throws Exception {
        Path workspace = workspace("fail");
        Path data = temp.resolve("data-fail");
        RecordingHook recording = new RecordingHook();
        Consumer<GenericApplicationContext> plugins = context -> context.registerBean("recording", KernelHook.class, () -> recording);

        try (TestServer server = new TestServer(data, workspace, claude("claude-no-such-model-0", "medium"), plugins)) {
            submit(server, "fail", "s1", "hi");
            JsonNode run = waitForLatestRun(server, "fail", "s1", Duration.ofMinutes(2));
            assertEquals("FAILED", run.get("endReason").asString(), run.toString());
        }

        List<EventEnvelope> journal = journal(data);
        List<ModelFailed> failures = events(journal, ModelFailed.class);
        assertEquals(1, failures.size(), failures.toString());
        assertEquals(FailureReason.INVALID_REQUEST, failures.getFirst().reason(), failures.getFirst().detail());
        assertFalse(failures.getFirst().retryable());
        assertEquals(List.of("INVALID_REQUEST"), recording.seen.get("onFailure"));
        System.out.println("[claude failure] detail=" + failures.getFirst().detail());
    }

    /**
     * 需求 5.4、4.9：流式进行中取消，再在同一个会话里继续。风险在第二步：被中断的 assistant 消息进了历史，
     * Anthropic 可能拒绝（空内容、结尾空白）。判定：取消后有部分文本，追问仍然成功。
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void cancelDuringStreamingThenTheConversationContinues() throws Exception {
        Path workspace = workspace("cancel");
        Path data = temp.resolve("data-cancel");

        try (TestServer server = new TestServer(data, workspace, claude(MODEL, "low"))) {
            CountDownLatch firstChunk = new CountDownLatch(1);
            HttpResponse<InputStream> stream = server.http.send(server.request("cancel", "/api/events").GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            Thread.ofVirtual().start(() -> {
                try (BufferedReader in = new BufferedReader(new InputStreamReader(stream.body()))) {
                    String event = "";
                    for (String line = in.readLine(); line != null; line = in.readLine()) {
                        if (line.startsWith("event:")) {
                            event = line.substring(6).trim();
                        } else if (line.startsWith("data:") && event.equals("chunk") && line.contains("\"kind\":\"text\"")) {
                            firstChunk.countDown();
                        }
                    }
                } catch (Exception closed) {
                    // 服务关闭时连接断开。
                }
            });

            submit(server, "cancel", "s1", "Write a 1500-word story about a lighthouse keeper. Do not use any tools.");
            assertTrue(firstChunk.await(3, TimeUnit.MINUTES), "no text chunk arrived");
            JsonNode runs = JSON.readTree(server.get("cancel", "/api/sessions/s1/runs").body()).get("runs");
            String runId = runs.get(0).get("run").asString();
            assertEquals(200, server.post("cancel", "/api/runs/" + runId + "/cancel", "{}").statusCode());
            JsonNode cancelled = waitForLatestRun(server, "cancel", "s1", Duration.ofMinutes(2));
            assertEquals("CANCELLED", cancelled.get("endReason").asString(), cancelled.toString());

            submit(server, "cancel", "s1", "In one sentence, what was the story about so far?");
            JsonNode followUp = waitForLatestRun(server, "cancel", "s1", Duration.ofMinutes(4));
            assertEquals("COMPLETED", followUp.get("endReason").asString(), followUp.toString());
            stream.body().close();
        }

        List<EventEnvelope> journal = journal(data);
        List<ModelInterrupted> interrupted = events(journal, ModelInterrupted.class);
        assertEquals(1, interrupted.size(), interrupted.toString());
        assertFalse(interrupted.getFirst().text().isBlank(), "the interruption should keep the text already streamed");
        assertFalse(lastText(journal).isBlank());
        System.out.println("[claude cancel] interruptedChars=" + interrupted.getFirst().text().length()
                + " followUp=" + lastText(journal));
        usage("cancel", journal);
    }

    /**
     * 多人同时调用：三个用户同时提交带工具的请求，模型并发上限为默认的 4。判定：三个运行都完成、
     * 各自只读到自己的文件、模型调用在时间上确实重叠（不是排队）。
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void threeUsersCallClaudeAtTheSameTime() throws Exception {
        List<String> users = List.of("ann", "bob", "cid");
        Path workspace = temp.resolve("workspace");
        for (String user : users) {
            Files.createDirectories(workspace.resolve(user));
            Files.writeString(workspace.resolve(user + "/note.txt"), "The secret word of " + user + " is " + user.toUpperCase() + "-7" + user.length() + ".\n");
        }
        Path data = temp.resolve("data-multi");
        Map<String, String> answers = new ConcurrentHashMap<>();
        Map<String, List<Instant[]>> modelSegments = new ConcurrentHashMap<>();

        try (TestServer server = new TestServer(data, workspace, claude(MODEL, "medium"))) {
            for (String user : users) {
                server.get(user, "/api/sessions");   // 先各自登录，提交时才是同时的。
            }
            CountDownLatch go = new CountDownLatch(1);
            List<Thread> threads = new ArrayList<>();
            for (String user : users) {
                threads.add(Thread.ofVirtual().start(() -> {
                    try {
                        go.await();
                        submit(server, user, "s-" + user, "Use read_file to read note.txt and tell me the secret word.");
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure);
                    }
                }));
            }
            go.countDown();
            for (Thread thread : threads) {
                thread.join();
            }
            for (String user : users) {
                JsonNode run = waitForLatestRun(server, user, "s-" + user, Duration.ofMinutes(5));
                assertEquals("COMPLETED", run.get("endReason").asString(), user + ": " + run);
                String runId = run.get("run").asString();
                JsonNode trace = JSON.readTree(server.get(user, "/api/runs/" + runId + "/trace").body());
                List<Instant[]> segments = new ArrayList<>();
                for (JsonNode segment : trace.get("segments")) {
                    if (segment.get("handler").asString().equals("ModelHandler")) {
                        segments.add(new Instant[] {Instant.parse(segment.get("from").asString()), Instant.parse(segment.get("to").asString())});
                    }
                }
                modelSegments.put(user, segments);
                JsonNode turns = JSON.readTree(server.get(user, "/api/runs/" + runId + "/turns").body()).get("turns");
                int last = turns.get(turns.size() - 1).get("number").asInt();
                answers.put(user, server.get(user, "/api/runs/" + runId + "/turns/" + last).body());
            }
        }

        for (String user : users) {
            String answer = answers.get(user);
            assertTrue(answer.contains(user.toUpperCase() + "-7" + user.length()), user + " did not get its own secret: " + answer);
            for (String other : users) {
                if (!other.equals(user)) {
                    assertFalse(answer.contains(other.toUpperCase() + "-7"), user + " saw " + other + "'s secret");
                }
            }
        }

        boolean overlapped = false;
        for (String a : users) {
            for (String b : users) {
                if (a.compareTo(b) >= 0) {
                    continue;
                }
                for (Instant[] x : modelSegments.get(a)) {
                    for (Instant[] y : modelSegments.get(b)) {
                        if (x[0].isBefore(y[1]) && y[0].isBefore(x[1])) {
                            overlapped = true;
                        }
                    }
                }
            }
        }
        assertTrue(overlapped, "model calls of different users never overlapped; they were serialised: " + describe(modelSegments));
        System.out.println("[claude multi-user] modelSegments=" + describe(modelSegments));
        usage("multi-user", journal(data));
    }

    /**
     * 风险：Sonnet 5.5 的 thinking 签名绑定 system、工具和之前的消息。同一会话里记忆变了，下一个运行的 system 就变了，
     * 回传旧 thinking 可能被拒（400）。判定：第一轮确实产生 thinking（否则跳过）；改记忆后同一会话的下一个运行正常完成。
     */
    @Test
    @Timeout(value = 6, unit = TimeUnit.MINUTES)
    void changingMemoryMidSessionKeepsThinkingValid() throws Exception {
        Path workspace = workspace("drift");
        Path data = temp.resolve("data-drift");
        try (TestServer server = new TestServer(data, workspace, claude(MODEL, "high"))) {
            submit(server, "drift", "s1", "Let a1 = 3 and a(n+1) = 2*a(n) - 1. Compute a12 modulo 7 step by step and double-check it.");
            assertEquals("COMPLETED", waitForLatestRun(server, "drift", "s1", Duration.ofMinutes(3)).get("endReason").asString());
            assertEquals(201, server.post("drift", "/api/memory",
                    "{\"name\":\"style\",\"type\":\"user\",\"description\":\"Prefers short answers\",\"content\":\"Short answers.\"}").statusCode());
            submit(server, "drift", "s1", "Now compute a13 modulo 7.");
            JsonNode second = waitForLatestRun(server, "drift", "s1", Duration.ofMinutes(3));
            System.out.println("[claude memory drift] second run=" + second);
            assertEquals("COMPLETED", second.get("endReason").asString(), second.toString());
        }
        List<EventEnvelope> journal = journal(data);
        long thinking = events(journal, ModelReplied.class).stream().flatMap(reply -> reply.content().stream()).filter(ReasoningBlock.class::isInstance).count();
        System.out.println("[claude memory drift] thinkingBlocks=" + thinking);
        org.junit.jupiter.api.Assumptions.assumeTrue(thinking > 0, "no thinking was produced, so this proves nothing");
        usage("memory drift", journal);
    }

    /** 该用户记忆目录里的条目文件名（不含索引）。 */
    private static List<String> memoryFiles(Path workspace, String user) throws Exception {
        Path dir = workspace.resolve(user).resolve("memory");
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var files = Files.list(dir)) {
            return files.map(file -> file.getFileName().toString())
                    .filter(name -> name.endsWith(".md") && !name.equals("MEMORY.md")).sorted().toList();
        }
    }

    private static List<String> toolCalls(List<EventEnvelope> journal) {
        return events(journal, ModelReplied.class).stream().flatMap(reply -> reply.content().stream())
                .filter(ToolCallBlock.class::isInstance).map(block -> ((ToolCallBlock) block).name()).toList();
    }

    private static List<String> systems(List<EventEnvelope> journal) {
        return events(journal, tantalum.value.event.ContextAssembled.class).stream()
                .map(tantalum.value.event.ContextAssembled::system).toList();
    }

    /**
     * memory 需求 8.2：会话 A 让模型记住“以后一律用 Java 25 语法”，会话 B 的新运行里 system 带上索引，
     * 模型回答时真的用了 Java 25 的写法。判定：A 调用了 memory_write 且文件存在；B 的 ContextAssembled.system
     * 含记忆索引；B 的回答含 {@code void main()}（无参）或 {@code IO.println}。
     */
    @Test
    @Timeout(value = 6, unit = TimeUnit.MINUTES)
    void memoryWrittenInOneSessionShapesTheAnswerInTheNext() throws Exception {
        Path workspace = workspace("mem");
        Path data = temp.resolve("data-mem");

        try (TestServer server = new TestServer(data, workspace, claude(MODEL, "medium"))) {
            submit(server, "mem", "s1", "From now on, whenever you show Java code, always use Java 25 syntax: the compact "
                    + "`void main()` entry point without arguments and `IO.println` for output, never the old "
                    + "`public static void main(String[] args)` form. Please remember this preference for my future sessions.");
            assertEquals("COMPLETED", waitForLatestRun(server, "mem", "s1", Duration.ofMinutes(3)).get("endReason").asString());
            assertFalse(memoryFiles(workspace, "mem").isEmpty(), "the model did not write a memory file");

            submit(server, "mem", "s2", "Show me the smallest complete Java program that prints Hello. Only the code, no explanation.");
            assertEquals("COMPLETED", waitForLatestRun(server, "mem", "s2", Duration.ofMinutes(3)).get("endReason").asString());
        }

        List<EventEnvelope> journal = journal(data);
        assertTrue(toolCalls(journal).contains("memory_write"), toolCalls(journal).toString());
        List<String> systems = systems(journal);
        assertFalse(systems.getFirst().contains("## Your memory index"), "first run starts with no memory");
        assertTrue(systems.getLast().contains("## Your memory index"), systems.getLast());
        String answer = lastText(journal);
        assertTrue(answer.contains("void main()") || answer.contains("IO.println"), "answer did not use Java 25 syntax:\n" + answer);
        assertFalse(answer.contains("String[] args"), "answer fell back to the old entry point:\n" + answer);
        System.out.println("[claude memory remember] files=" + memoryFiles(workspace, "mem") + " answer=" + answer.replace("\n", " | "));
        usage("memory remember", journal);
    }

    /**
     * memory 需求 8.3：用户让它忘记。先用接口种一条记忆，再在新会话里说“忘掉关于 Java 版本的记忆”。
     * 判定：调用了 memory_delete、文件消失、再下一个运行的 system 不含索引。
     */
    @Test
    @Timeout(value = 6, unit = TimeUnit.MINUTES)
    void askingToForgetDeletesTheMemory() throws Exception {
        Path workspace = workspace("forget");
        Path data = temp.resolve("data-forget");

        try (TestServer server = new TestServer(data, workspace, claude(MODEL, "medium"))) {
            HttpResponse<String> seeded = server.post("forget", "/api/memory",
                    "{\"name\":\"java-version\",\"type\":\"user\",\"description\":\"Always answer with Java 25 syntax\","
                    + "\"content\":\"The user wants Java 25 syntax in every example.\"}");
            assertEquals(201, seeded.statusCode(), seeded.body());

            submit(server, "forget", "s1", "Please forget what you remembered about my preferred Java version.");
            assertEquals("COMPLETED", waitForLatestRun(server, "forget", "s1", Duration.ofMinutes(3)).get("endReason").asString());
            assertEquals(List.of(), memoryFiles(workspace, "forget"), "the memory file should be gone");

            submit(server, "forget", "s2", "Say hi in one word.");
            assertEquals("COMPLETED", waitForLatestRun(server, "forget", "s2", Duration.ofMinutes(3)).get("endReason").asString());
        }

        List<EventEnvelope> journal = journal(data);
        assertTrue(toolCalls(journal).contains("memory_delete"), toolCalls(journal).toString());
        List<String> systems = systems(journal);
        assertTrue(systems.getFirst().contains("java-version"), "first run saw the seeded index");
        assertFalse(systems.getLast().contains("## Your memory index"), systems.getLast());
        usage("memory forget", journal);
    }

    private static String describe(Map<String, List<Instant[]>> segments) {
        StringBuilder text = new StringBuilder();
        segments.forEach((user, list) -> {
            text.append(user).append('=');
            for (Instant[] segment : list) {
                text.append('[').append(segment[0]).append("→").append(segment[1]).append(']');
            }
            text.append(' ');
        });
        return text.toString();
    }

    @SuppressWarnings("unused")
    private static void requireNonNull(Object value) {
        assertNotNull(value);
    }
}
