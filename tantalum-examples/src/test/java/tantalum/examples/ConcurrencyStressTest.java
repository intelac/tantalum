package tantalum.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.support.GenericApplicationContext;
import tantalum.kernel.Kernel;
import tantalum.kernel.Replay;
import tantalum.kernel.hook.HookChain;
import tantalum.kernel.hook.KernelHook;
import tantalum.kernel.step.BuiltinSteps;
import tantalum.kernel.table.TransitionTable;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.model.ChunkListener;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.ModelOutcome;
import tantalum.shell.plugin.AfterToolHook;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.ToolOutput;
import tantalum.shell.plugin.WorkType;
import tantalum.shell.store.SessionStore;
import tantalum.shell.tool.GrepTool;
import tantalum.shell.tool.ReadFileTool;
import tantalum.shell.tool.ToolCatalog;
import tantalum.shell.web.TestServer;
import tantalum.value.command.CallModel;
import tantalum.value.event.CommandDropped;
import tantalum.value.event.EventEnvelope;
import tantalum.value.json.Json;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.Message;
import tantalum.value.model.Prompt;
import tantalum.value.model.Role;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolResultBlock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 多用户同时运行、每个运行里多轮工具（并行组 + 被拦截的调用）和多个插件（只读策略、截断、引用核对工作），
 * 其中一部分运行在模型调用进行中被取消。检查的不是“没抛异常”，而是并发下的不变量：
 * 每个用户的 prompt 只含自己的文件内容；SSE 只收到自己的事件；没有命令丢失；
 * 从 journal 回放得到的 prompt 和实时发给模型的逐个相同；重启后由回放重建的对话视图和实时视图逐字节相同。
 * 不用真实模型，整个场景重复若干轮。第 2 轮激进回收（任务 32）：会话一空闲就离开内存、运行结束就写快照，
 * 全部结束后每个会话再同时提交一轮，迫使 32 个会话并发载入；取消后迟到的模型回复也会落到已回收的会话上。
 */
class ConcurrencyStressTest {

    private static final int USERS = 16;
    /** 每个用户同时开的会话数。 */
    private static final int SESSIONS = 2;
    /** 整个场景重复的轮数。每轮约一分钟，大头是 16 个账号的 Argon2 登录，不是运行本身。 */
    private static final int ROUNDS = 2;
    private static final int TRUNCATE_AT = 120;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path temp;

    /** 无状态、线程安全：只看 prompt 内容决定下一步，所以多个用户可以同时调用。 */
    static final class StatelessModel implements ModelClient {
        final List<Prompt> prompts = Collections.synchronizedList(new ArrayList<>());
        private final Set<String> slowUsers;

        StatelessModel(Set<String> slowUsers) {
            this.slowUsers = slowUsers;
        }

        /** 第一条用户消息形如 {@code I am u03 in s-u03-2.}。 */
        private static String intro(Prompt prompt) {
            for (Message message : prompt.messages()) {
                if (message.role() == Role.USER) {
                    for (ContentBlock block : message.content()) {
                        if (block instanceof TextBlock text && text.text().startsWith("I am ")) {
                            return text.text();
                        }
                    }
                }
            }
            throw new AssertionError("prompt without the user's introduction: " + prompt.messages());
        }

        static String userOf(Prompt prompt) {
            String intro = intro(prompt);
            return intro.substring(5, intro.indexOf(" in "));
        }

        static String sessionOf(Prompt prompt) {
            String intro = intro(prompt);
            return intro.substring(intro.indexOf(" in ") + 4, intro.indexOf('.'));
        }

        @Override
        public ModelOutcome call(Prompt prompt, ChunkListener chunks) {
            prompts.add(prompt);
            String user = userOf(prompt);
            int results = 0;
            boolean blocked = false;
            for (Message message : prompt.messages()) {
                for (ContentBlock block : message.content()) {
                    if (block instanceof ToolResultBlock result) {
                        results++;
                        blocked |= result.text().contains("Tool call blocked");
                    }
                }
            }
            if (results == 0) {
                return tools(call("read_file", "path", "a.txt"), call("read_file", "path", "b.txt"),
                        call("read_file", "path", "c.txt"), call("grep", "pattern", "SECRET"));
            }
            if (!blocked) {
                if (slowUsers.contains(user)) {
                    try {
                        Thread.sleep(400);
                    } catch (InterruptedException cancelled) {
                        Thread.currentThread().interrupt();
                        return new ModelOutcome.Interrupted("partial for " + user);
                    }
                }
                return tools(call("write_file", "path", "out.txt"));
            }
            String answer = "The secret of " + user + " is in a.txt:1.";
            chunks.onText(answer);
            return new ModelOutcome.Replied(List.of(new TextBlock(answer)), StopReason.END_TURN, "stateless", null);
        }

        private static ToolCallBlock call(String tool, String argument, String value) {
            String raw = "{\"" + argument + "\":\"" + value + "\"}";
            return new ToolCallBlock("c-" + UUID.randomUUID(), tool, raw, new Json.Obj(Map.of(argument, new Json.Str(value))));
        }

        private static ModelOutcome tools(ToolCallBlock... calls) {
            return new ModelOutcome.Replied(List.of(calls), StopReason.TOOL_USE, "stateless", null);
        }
    }

    private static Tool writeFile(AtomicInteger executions) {
        Json schema = new Json.Obj(Map.of("type", new Json.Str("object"),
                "properties", new Json.Obj(Map.of("path", new Json.Obj(Map.of("type", new Json.Str("string")))))));
        return new Tool() {
            @Override public String name() { return "write_file"; }
            @Override public String description() { return "Write a file."; }
            @Override public Json schema() { return schema; }
            @Override public ToolOutput run(ToolContext context, Json arguments) {
                executions.incrementAndGet();
                return ToolOutput.ok("written");
            }
        };
    }

    private static List<KernelHook> hooks() {
        return List.of(new ReadOnlyHook(Set.of("read_file", "grep")), new CitationCheckHook());
    }

    private static String user(int index) {
        return String.format("u%02d", index);
    }

    private static String marker(String user) {
        return "USER-" + user + "-SECRET";
    }

    private static String session(String user, int number) {
        return "s-" + user + "-" + number;
    }

    /** 等全部会话离开内存，再让每个会话同时提交第二轮：每个都要从快照载入，载入和别的用户的事件、回收交错。 */
    private static void secondTurnFromCold(TestServer server, Instant deadline, Map<String, String> messagesBefore) throws Exception {
        tantalum.shell.boot.Engine engine = server.context.getBean(tantalum.shell.boot.Engine.class);
        while (!engine.state().sessions().isEmpty() && Instant.now().isBefore(deadline)) {
            Thread.sleep(20);
        }
        assertTrue(engine.state().sessions().isEmpty(), "idle sessions must have left memory: " + engine.state().sessions().keySet());
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < USERS; i++) {
            for (int n = 1; n <= SESSIONS; n++) {
                String user = user(i);
                String session = session(user, n);
                threads.add(Thread.ofVirtual().start(() -> {
                    try {
                        go.await();
                        HttpResponse<String> response = server.post(user, "/api/sessions/" + session + "/submissions",
                                "{\"requestId\":\"again-" + session + "\",\"mode\":\"prompt\",\"text\":\"Again, please.\"}");
                        assertEquals(200, response.statusCode(), response.body());
                    } catch (Throwable failure) {
                        failures.add(failure);
                    }
                }));
            }
        }
        go.countDown();
        for (Thread thread : threads) {
            thread.join();
        }
        assertTrue(failures.isEmpty(), failures.toString());
        for (int i = 0; i < USERS; i++) {
            for (int n = 1; n <= SESSIONS; n++) {
                String user = user(i);
                String session = session(user, n);
                JsonNode second = null;
                while (Instant.now().isBefore(deadline)) {
                    JsonNode runs = JSON.readTree(server.get(user, "/api/sessions/" + session + "/runs").body()).get("runs");
                    if (runs != null && runs.size() == 2 && runs.get(1).has("endReason")) {
                        second = runs.get(1);
                        break;
                    }
                    Thread.sleep(20);
                }
                assertTrue(second != null, "second run of " + session + " did not end");
                assertEquals("COMPLETED", second.get("endReason").asString(), session);
                messagesBefore.put(session, server.get(user, "/api/sessions/" + session + "/messages").body());
            }
        }
    }

    private static final java.util.regex.Pattern USER_ID = java.util.regex.Pattern.compile("USER-(u\\d\\d)");

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void sixteenUsersTwoSessionsEachWithParallelToolsHooksWorkAndCancelsKeepEveryInvariant() throws Exception {
        for (int round = 1; round <= ROUNDS; round++) {
            runRound(round);
        }
    }

    private void runRound(int round) throws Exception {
        Path workspace = Files.createDirectories(temp.resolve("round" + round + "/workspace"));
        Path data = temp.resolve("round" + round + "/data");
        Set<String> slow = new java.util.HashSet<>();
        for (int i = 0; i < USERS; i++) {
            String user = user(i);
            Files.createDirectory(workspace.resolve(user));
            Files.writeString(workspace.resolve(user + "/a.txt"), marker(user) + " first line\nsecond line\n");
            Files.writeString(workspace.resolve(user + "/b.txt"), (marker(user) + " filler ").repeat(20) + "\n");
            Files.writeString(workspace.resolve(user + "/c.txt"), "nothing to see for " + user + "\n");
            if (i % 4 == 3) {
                slow.add(user);
            }
        }
        StatelessModel model = new StatelessModel(slow);
        AtomicInteger writes = new AtomicInteger();
        Consumer<GenericApplicationContext> plugins = context -> {
            context.registerBean("writeFile", Tool.class, () -> writeFile(writes));
            context.registerBean("readOnly", KernelHook.class, () -> hooks().get(0));
            context.registerBean("citationCheck", KernelHook.class, () -> hooks().get(1));
            context.registerBean("citationWork", WorkType.class, CitationWork::new);
            context.registerBean("truncate", AfterToolHook.class, () -> new TruncateOutputHook(TRUNCATE_AT));
        };

        Map<String, String> messagesBefore = new LinkedHashMap<>();
        Map<String, String> endReasons = new LinkedHashMap<>();
        Map<String, List<String>> sseLines = new ConcurrentHashMap<>();
        List<HttpResponse<InputStream>> streams = new ArrayList<>();
        boolean evict = round == 2;
        String[] args = evict ? new String[] {"--tantalum.claude.concurrency=16", "--tantalum.residency.idle=0s",
                "--tantalum.residency.max-resident=1000", "--tantalum.residency.snapshot-events=1", "--tantalum.residency.sweep-interval=0s"}
                : new String[] {"--tantalum.claude.concurrency=16"};
        try (TestServer server = new TestServer(data, workspace, model, plugins, args)) {
            for (int i = 0; i < USERS; i++) {
                server.get(user(i), "/api/sessions");     // 先登录，提交才是同时的
            }
            for (int i = 0; i < 4; i++) {
                String user = user(i);
                List<String> lines = Collections.synchronizedList(new ArrayList<>());
                sseLines.put(user, lines);
                HttpResponse<InputStream> stream = server.http.send(server.request(user, "/api/events").GET().build(),
                        HttpResponse.BodyHandlers.ofInputStream());
                streams.add(stream);
                Thread.ofVirtual().start(() -> {
                    try (BufferedReader in = new BufferedReader(new InputStreamReader(stream.body()))) {
                        in.lines().forEach(lines::add);
                    } catch (Exception closed) {
                        // 服务关闭时连接断开。
                    }
                });
            }

            CountDownLatch go = new CountDownLatch(1);
            List<Thread> threads = new ArrayList<>();
            List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
            for (int i = 0; i < USERS; i++) {
                for (int n = 1; n <= SESSIONS; n++) {
                    String user = user(i);
                    String session = session(user, n);
                    boolean cancelThisOne = slow.contains(user) && n == 1;
                    threads.add(Thread.ofVirtual().start(() -> {
                        try {
                            go.await();
                            HttpResponse<String> response = server.post(user, "/api/sessions/" + session + "/submissions",
                                    "{\"requestId\":\"r-" + session + "\",\"mode\":\"prompt\",\"text\":\"I am " + user + " in " + session + ". Find my secret.\"}");
                            assertEquals(200, response.statusCode(), response.body());
                            if (cancelThisOne) {
                                String runId = null;
                                for (int attempt = 0; runId == null && attempt < 500; attempt++) {
                                    JsonNode runs = JSON.readTree(server.get(user, "/api/sessions/" + session + "/runs").body()).get("runs");
                                    if (runs != null && !runs.isEmpty()) {
                                        runId = runs.get(0).get("run").asString();
                                    } else {
                                        Thread.sleep(5);
                                    }
                                }
                                Thread.sleep(150);  // 让它走进慢的那次模型调用
                                server.post(user, "/api/runs/" + runId + "/cancel", "{}");
                            }
                        } catch (Throwable failure) {
                            failures.add(failure);
                        }
                    }));
                }
            }
            go.countDown();
            for (Thread thread : threads) {
                thread.join();
            }
            assertTrue(failures.isEmpty(), failures.toString());

            Instant deadline = Instant.now().plusSeconds(60);
            for (int i = 0; i < USERS; i++) {
                for (int n = 1; n <= SESSIONS; n++) {
                    String user = user(i);
                    String session = session(user, n);
                    JsonNode run = null;
                    while (Instant.now().isBefore(deadline)) {
                        JsonNode runs = JSON.readTree(server.get(user, "/api/sessions/" + session + "/runs").body()).get("runs");
                        if (runs != null && !runs.isEmpty() && runs.get(0).has("endReason")) {
                            run = runs.get(0);
                            break;
                        }
                        Thread.sleep(20);
                    }
                    assertTrue(run != null, "round " + round + ": run of " + session + " did not end");
                    endReasons.put(session, run.get("endReason").asString());
                    messagesBefore.put(session, settledMessages(server, user, session));
                }
            }
            if (evict) {
                secondTurnFromCold(server, deadline, messagesBefore);
            }
            Thread.sleep(200);   // 让 SSE 把最后的条目送到
            for (HttpResponse<InputStream> stream : streams) {
                stream.body().close();
            }
        }

        // 1. 结束方式符合计划：没取消的全部完成（包括慢用户的第二个会话），取消的要么已取消要么赶在取消前完成了。
        int cancelled = 0;
        for (int i = 0; i < USERS; i++) {
            for (int n = 1; n <= SESSIONS; n++) {
                String user = user(i);
                String session = session(user, n);
                String reason = endReasons.get(session);
                if (slow.contains(user) && n == 1) {
                    assertTrue(reason.equals("CANCELLED") || reason.equals("COMPLETED"), session + ": " + reason);
                    cancelled += reason.equals("CANCELLED") ? 1 : 0;
                } else {
                    assertEquals("COMPLETED", reason, session);
                }
            }
        }
        assertTrue(cancelled > 0, "round " + round + ": no cancel landed during a model call; the slow path was not exercised");

        // 2. 只读策略在并发下也拦住了每一次写；截断钩子改了每个用户的 b.txt。
        assertEquals(0, writes.get(), "write_file must never execute");
        for (Prompt prompt : List.copyOf(model.prompts)) {
            String user = StatelessModel.userOf(prompt);
            for (Message message : prompt.messages()) {
                for (ContentBlock block : message.content()) {
                    if (block instanceof ToolResultBlock result && result.text().contains("USER-")) {
                        assertTrue(result.text().contains(marker(user)), user + " received someone else's file: " + result.text());
                        // 截断可能把标记切在中间，所以只看完整的用户编号。
                        java.util.regex.Matcher ids = USER_ID.matcher(result.text());
                        while (ids.find()) {
                            assertEquals(user, ids.group(1), user + " received a mixed result: " + result.text());
                        }
                        if (result.text().contains("filler")) {
                            assertTrue(result.text().contains("more characters truncated"), "b.txt was not truncated: " + result.text());
                        }
                    }
                }
            }
        }

        // 3. SSE 只送自己的事件。
        for (Map.Entry<String, List<String>> entry : sseLines.entrySet()) {
            List<String> lines = entry.getValue();
            synchronized (lines) {
                for (int n = 1; n <= SESSIONS; n++) {
                    String session = session(entry.getKey(), n);
                    assertTrue(lines.stream().anyMatch(line -> line.contains("\"session\":\"" + session + "\"")), session + " got no events");
                }
                for (String line : lines) {
                    if (line.startsWith("data:") && line.contains("\"session\":\"")) {
                        assertTrue(line.contains("\"session\":\"s-" + entry.getKey() + "-"), entry.getKey() + " saw " + line);
                    }
                }
            }
        }

        // 4. journal：没有命令丢失；回放得到的 prompt 和实时发给模型的逐个相同。
        List<EventEnvelope> journal;
        journal = new SessionStore(data, EventCodec.withBuiltins()).readAll();
        assertTrue(journal.stream().noneMatch(envelope -> envelope.event() instanceof CommandDropped), "a command was dropped");
        Map<String, Integer> requests = new java.util.HashMap<>();
        for (EventEnvelope envelope : journal) {
            if (envelope.event() instanceof tantalum.value.event.UserPrompt prompt) {
                requests.merge(envelope.principal().value() + "/" + prompt.request().value(), 1, Integer::sum);
            }
        }
        assertEquals(USERS * SESSIONS * (evict ? 2 : 1), requests.size(), "every accepted submission is in the journal");
        assertTrue(requests.values().stream().allMatch(count -> count == 1), "no submission was written twice: " + requests);
        TransitionTable table = new TransitionTable();
        BuiltinSteps.register(table);
        ToolCatalog catalog = new ToolCatalog();
        catalog.register(new ReadFileTool());
        catalog.register(new GrepTool());
        catalog.register(writeFile(new AtomicInteger()));
        Kernel kernel = new Kernel(table, new HookChain(hooks()), catalog.safety(), 10);
        Replay.Result replay = Replay.run(kernel, journal);
        Map<String, List<Prompt>> replayed = new LinkedHashMap<>();
        for (var command : replay.commands()) {
            if (command instanceof CallModel call) {
                replayed.computeIfAbsent(call.run().session().value(), key -> new ArrayList<>()).add(call.prompt());
            }
        }
        Map<String, List<Prompt>> live = new LinkedHashMap<>();
        for (Prompt prompt : List.copyOf(model.prompts)) {
            live.computeIfAbsent(StatelessModel.sessionOf(prompt), key -> new ArrayList<>()).add(prompt);
        }
        assertEquals(replayed.keySet(), live.keySet());
        for (String session : live.keySet()) {
            assertEquals(replayed.get(session), live.get(session), "prompts differ for " + session);
        }

        // 5. 重启后由回放重建的对话视图和实时视图逐字节相同，且不会再调用模型。
        StatelessModel mustNotRun = new StatelessModel(Set.of());
        try (TestServer restarted = new TestServer(data, workspace, mustNotRun, plugins)) {
            for (int i = 0; i < USERS; i++) {
                for (int n = 1; n <= SESSIONS; n++) {
                    String session = session(user(i), n);
                    assertEquals(messagesBefore.get(session), restarted.get(user(i), "/api/sessions/" + session + "/messages").body(),
                            "round " + round + ": replayed view differs for " + session);
                }
            }
            Thread.sleep(100);
            assertEquals(0, mustNotRun.prompts.size());
        }
        System.out.println("[stress round " + round + "] users=" + USERS + " sessions=" + (USERS * SESSIONS) + " modelCalls=" + model.prompts.size()
                + " events=" + journal.size() + " cancelled=" + cancelled);
    }

    /**
     * 运行结束后，被取消的模型调用还可能晚一点送回“被中断”的结果，追加到历史里。
     * 连续两次读到同样的内容才算稳定，再拿它和重启后回放的视图比较。
     */
    private static String settledMessages(TestServer server, String user, String session) throws Exception {
        String path = "/api/sessions/" + session + "/messages";
        String previous = server.get(user, path).body();
        for (int i = 0; i < 50; i++) {
            Thread.sleep(200);
            String current = server.get(user, path).body();
            if (current.equals(previous)) {
                return current;
            }
            previous = current;
        }
        throw new AssertionError("messages of " + session + " kept changing");
    }
}
