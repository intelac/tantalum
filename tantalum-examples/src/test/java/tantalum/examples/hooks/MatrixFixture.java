package tantalum.examples.hooks;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import tantalum.kernel.Kernel;
import tantalum.kernel.Limits;
import tantalum.kernel.Replay;
import tantalum.kernel.hook.HookChain;
import tantalum.kernel.hook.KernelHook;
import tantalum.kernel.step.BuiltinSteps;
import tantalum.kernel.table.TransitionTable;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.journal.SessionFileReader;
import tantalum.shell.journal.SessionPaths;
import tantalum.shell.plugin.AfterToolHook;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.ToolOutput;
import tantalum.shell.plugin.WorkType;
import tantalum.shell.web.TestServer;
import tantalum.value.command.CallModel;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.ModelFailed;
import tantalum.value.event.ToolFinished;
import tantalum.value.event.WorkDone;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.Message;
import tantalum.value.model.Prompt;
import tantalum.value.model.Role;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolResultBlock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 真实管线的测试台：一个 {@link TestServer}（真实的环、journal、处理器），模型和工作是 {@link MatrixModel}、{@link MatrixWork}。
 * 一个测试类共用一个服务，每格一个新会话（design 第 4 节“太慢就共用服务”）；钩子按会话生效，互不影响。
 * 提供每格要查的四样（结束原因、journal 事件序列、对话条目、prompt）的简写，以及“重开同一数据目录后视图逐字节相同”的比较。
 */
final class MatrixFixture implements AutoCloseable {

    static final String USER = "alice";
    static final Principal PRINCIPAL = new Principal(USER);
    static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration WAIT = Duration.ofSeconds(30);

    final Path data;
    final Path workspace;
    final MatrixModel model = new MatrixModel();
    final MatrixWork work = new MatrixWork(MatrixWork.TYPE);
    final MatrixWork source = new MatrixWork(MatrixWork.SOURCE);
    final CountingTool echo = new CountingTool("echo");
    private final List<KernelHook> hooks = new ArrayList<>();
    private final List<AfterToolHook> afterHooks = new ArrayList<>();
    private final List<WorkType> workTypes = new ArrayList<>(List.of(work, source));
    private final List<Tool> tools = new ArrayList<>(List.of(echo));
    private final String[] args;
    private final Set<String> sessions = new LinkedHashSet<>();
    private final AtomicInteger requests = new AtomicInteger();
    private TestServer server;

    MatrixFixture(Path temp, String... args) {
        this.data = temp.resolve("data");
        this.workspace = temp.resolve("workspace");
        this.args = args;
        try {
            Files.createDirectories(workspace.resolve(USER));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    MatrixFixture hook(KernelHook hook) {
        hooks.add(hook);
        return this;
    }

    MatrixFixture afterHook(AfterToolHook hook) {
        afterHooks.add(hook);
        return this;
    }

    MatrixFixture workType(WorkType type) {
        workTypes.add(type);
        return this;
    }

    MatrixFixture tool(Tool tool) {
        tools.add(tool);
        return this;
    }

    MatrixFixture start() {
        server = new TestServer(data, workspace, model, context -> {
            for (int i = 0; i < hooks.size(); i++) {
                KernelHook hook = hooks.get(i);
                context.registerBean("hook" + i, KernelHook.class, () -> hook);
            }
            for (int i = 0; i < afterHooks.size(); i++) {
                AfterToolHook hook = afterHooks.get(i);
                context.registerBean("after" + i, AfterToolHook.class, () -> hook);
            }
            for (int i = 0; i < workTypes.size(); i++) {
                WorkType type = workTypes.get(i);
                context.registerBean("work" + i, WorkType.class, () -> type);
            }
            for (int i = 0; i < tools.size(); i++) {
                Tool tool = tools.get(i);
                context.registerBean("tool" + i, Tool.class, () -> tool);
            }
        }, args);
        return this;
    }

    TestServer server() {
        return server;
    }

    /** 提交一条 prompt。文字里带上 {@code cell=<会话>}，模型据此认出会话。 */
    void submit(String session, String text) throws Exception {
        sessions.add(session);
        String body = "{\"requestId\":\"r" + requests.incrementAndGet() + "\",\"mode\":\"prompt\",\"text\":"
                + JSON.writeValueAsString("cell=" + session + " " + text) + "}";
        HttpResponse<String> response = server.post(USER, "/api/sessions/" + session + "/submissions", body);
        assertEquals(200, response.statusCode(), response.body());
    }

    void compact(String session) throws Exception {
        HttpResponse<String> response = server.post(USER, "/api/sessions/" + session + "/compact", "{}");
        assertEquals(200, response.statusCode(), response.body());
    }

    void cancel(String run) throws Exception {
        HttpResponse<String> response = server.post(USER, "/api/runs/" + run + "/cancel", "{}");
        assertEquals(200, response.statusCode(), response.body());
    }

    JsonNode runs(String session) throws Exception {
        return JSON.readTree(server.get(USER, "/api/sessions/" + session + "/runs").body()).get("runs");
    }

    /** 等会话有 {@code count} 个运行且最后一个已结束，返回它的“原因: 详情”。 */
    String awaitEnd(String session, int count) throws Exception {
        Instant deadline = Instant.now().plus(WAIT);
        JsonNode runs = null;
        while (Instant.now().isBefore(deadline)) {
            runs = runs(session);
            if (runs != null && runs.size() == count && runs.get(count - 1).has("endReason")) {
                awaitDurable(session, deadline);
                return end(runs.get(count - 1));
            }
            Thread.sleep(5);
        }
        throw new AssertionError(session + ": runs did not settle: " + runs);
    }

    /**
     * 等会话视图里的每一步都写进了会话文件：Cleanroom 先发布状态、用户通道再写这一行，运行视图里已经结束时最后一行可能还没落盘，
     * 紧接着读 journal 会少一行（step=7 的回归里见过 HookMatrixTest 少了 ContextAssembled）。
     */
    private void awaitDurable(String session, Instant deadline) throws Exception {
        long seq = JSON.readTree(messages(session)).get("seq").asLong();
        while (Instant.now().isBefore(deadline)) {
            List<EventEnvelope> journal = journal(session);
            if (!journal.isEmpty() && journal.getLast().seq() >= seq) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError(session + ": the journal never reached seq " + seq);
    }

    /** 第一个运行的 ID，等它出现。 */
    String awaitRun(String session) throws Exception {
        Instant deadline = Instant.now().plus(WAIT);
        while (Instant.now().isBefore(deadline)) {
            JsonNode runs = runs(session);
            if (runs != null && !runs.isEmpty()) {
                return runs.get(0).get("run").asString();
            }
            Thread.sleep(5);
        }
        throw new AssertionError(session + ": no run started");
    }

    static String end(JsonNode run) {
        JsonNode detail = run.get("detail");
        return run.get("endReason").asString() + (detail == null || detail.isNull() ? "" : ": " + detail.asString());
    }

    List<EventEnvelope> journal(String session) {
        try {
            return new SessionFileReader(EventCodec.withBuiltins())
                    .readAll(new SessionPaths(data).sessionFile(PRINCIPAL, new SessionId(session)));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** journal 事件的类型序列；工作、失败和工具结果带上关键内容。 */
    List<String> events(String session) {
        return journal(session).stream().map(envelope -> event(envelope)).toList();
    }

    private static String event(EventEnvelope envelope) {
        return switch (envelope.event()) {
            case WorkDone done -> "WorkDone[" + done.workType() + (done.failed() ? " failed: " + text(done.result()) : "") + "]";
            case ModelFailed failed -> "ModelFailed[" + failed.reason() + "]";
            case ToolFinished tool -> "ToolFinished[" + tool.text() + "]";
            default -> envelope.event().type();
        };
    }

    private static String text(Json value) {
        return value instanceof Json.Str text ? text.value() : String.valueOf(value);
    }

    /** 等 journal 里出现 {@code count} 个事件（取消后迟到的结果要过一会儿才落盘）。 */
    List<String> awaitEvents(String session, int count) throws Exception {
        Instant deadline = Instant.now().plus(WAIT);
        List<String> events = events(session);
        while (events.size() < count && Instant.now().isBefore(deadline)) {
            Thread.sleep(10);
            events = events(session);
        }
        return events;
    }

    String messages(String session) throws Exception {
        return server.get(USER, "/api/sessions/" + session + "/messages").body();
    }

    /** 对话页条目的简写。 */
    List<String> items(String session) throws Exception {
        List<String> out = new ArrayList<>();
        for (JsonNode item : JSON.readTree(messages(session)).get("items")) {
            out.add(item(item));
        }
        return out;
    }

    private static String item(JsonNode item) {
        String kind = item.get("kind").asString();
        return switch (kind) {
            case "user" -> "user";
            case "text" -> "text:" + shorten(item.get("text").asString()) + (item.path("interrupted").asBoolean() ? " (interrupted)" : "");
            case "tool" -> {
                JsonNode result = item.path("result");
                // 合成结果（运行提前结束时补上的）只写代码，原文太长
                String shown = field(item, "result") == null ? ""
                        : field(result, "code") == null ? ":" + result.get("text").asString() : ":(" + field(result, "code") + ")";
                yield "tool:" + item.get("call").get("name").asString() + ":" + item.get("status").asString() + shown;
            }
            case "run" -> {
                String state = field(item, "endReason") == null ? field(item, "phase") : field(item, "endReason");
                String detail = field(item, "detail") == null ? "" : ": " + field(item, "detail");
                String failure = field(item, "failure") == null ? "" : " after " + field(item, "failure");
                yield "run:" + state + detail + failure;
            }
            case "compaction" -> "compaction:" + field(item, "trigger")
                    + (field(item, "failure") == null ? " ok" : " failed: " + field(item, "failure"));
            default -> kind;
        };
    }

    /** 空字段在接口里省略，也可能是 null，两种都当作没有。 */
    private static String field(JsonNode node, String name) {
        JsonNode value = node.path(name);
        return value.isMissingNode() || value.isNull() ? null : value.isValueNode() ? value.asString() : value.toString();
    }

    /** 长文字只留开头，表格里写得下。 */
    static String shorten(String text) {
        return text.length() <= 40 ? text : text.substring(0, 30) + "...";
    }

    /** 模型收到的 prompt 的简写：消息数、最后一块是什么、补丁有没有生效、是不是摘要调用。 */
    List<String> prompts(String session) {
        return model.prompts(session).stream().map(MatrixFixture::prompt).toList();
    }

    static String prompt(Prompt prompt) {
        StringBuilder out = new StringBuilder(prompt.output().toolsAllowed() ? "" : "summary ");
        // 钩子的补丁是追加的 system 消息（hook-patch），不算在消息数里；"patched" 看历史里有没有它的说明和工具
        List<Message> spoken = prompt.messages().stream().filter(message -> message.role() != Role.SYSTEM).toList();
        out.append("msgs=").append(spoken.size());
        Message last = spoken.getLast();
        ContentBlock block = last.content().getLast();
        out.append(" last=").append(switch (block) {
            case ToolResultBlock result -> "result" + (result.error() ? "!" : "") + ":" + result.text();
            case TextBlock text -> last.role() == Role.USER ? "user" : "assistant";
            default -> block.getClass().getSimpleName();
        });
        boolean system = prompt.messages().stream().filter(message -> message.role() == Role.SYSTEM)
                .flatMap(message -> message.content().stream())
                .anyMatch(piece -> piece instanceof tantalum.value.model.HookTextBlock note && note.text().equals(MatrixHook.PATCH_TEXT));
        boolean tool = prompt.tools().contains(MatrixHook.PATCH_TOOL.deferred(true));
        if (system || tool) {
            out.append(system && tool ? " patched" : " half-patched");
        }
        return out.toString();
    }

    /** 用同样的钩子从 journal 回放这个会话，得到的 prompt 要和实时发给模型的逐个相同。 */
    List<Prompt> replayedPrompts(String session) {
        TransitionTable table = new TransitionTable();
        BuiltinSteps.register(table);
        Kernel kernel = new Kernel(table, new HookChain(hooks), (tool, arguments) -> false, Limits.DEFAULT);
        return Replay.run(kernel, journal(session)).commands().stream()
                .filter(CallModel.class::isInstance).map(command -> ((CallModel) command).prompt()).toList();
    }

    /** 关掉服务（按 {@code shutdown-wait} 等在途的命令，到时中断），数据目录留着。 */
    void stop() {
        server.close();
    }

    /** 关掉再用同一个数据目录打开。 */
    void reopen() {
        stop();
        start();
    }

    /**
     * 需求 4.5：记下每个用过的会话的对话视图和运行视图，用同一个数据目录重开，重放出来的要逐字节相同，且模型、工作都不再被调用。
     */
    void reopenAndCompare() throws Exception {
        Map<String, String> before = snapshot();
        int calls = model.total();
        reopen();
        Map<String, String> after = snapshot();
        for (String key : before.keySet()) {
            assertEquals(before.get(key), after.get(key), "replayed view differs: " + key);
        }
        Thread.sleep(100);
        assertEquals(calls, model.total(), "reopening called the model");
    }

    private Map<String, String> snapshot() throws Exception {
        Map<String, String> views = new LinkedHashMap<>();
        for (String session : sessions) {
            views.put(session + " messages", messages(session));
            views.put(session + " runs", server.get(USER, "/api/sessions/" + session + "/runs").body());
        }
        return views;
    }

    @Override
    public void close() {
        if (server != null) {
            server.close();
        }
    }

    /** 只对会话名满足 {@code sessions} 的会话生效的钩子：示例插件本身不分会话，共用服务时用它隔开。 */
    static KernelHook scoped(Predicate<String> sessions, KernelHook inner) {
        return new Scoped(sessions, inner);
    }

    /** 回 {@code <名字>-ok} 的工具，按参数里的 {@code cell} 计数。 */
    static final class CountingTool implements Tool {
        private final String name;
        private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();

        CountingTool(String name) {
            this.name = name;
        }

        @Override public String name() { return name; }
        @Override public String description() { return "Test tool " + name + "."; }
        @Override public Json schema() { return new Json.Obj(Map.of("type", new Json.Str("object"))); }

        @Override
        public ToolOutput run(ToolContext context, Json arguments) {
            String cell = arguments instanceof Json.Obj obj && obj.fields().get("cell") instanceof Json.Str value ? value.value() : "";
            calls.computeIfAbsent(cell, key -> new AtomicInteger()).incrementAndGet();
            return ToolOutput.ok(name + "-ok");
        }

        int calls(String cell) {
            AtomicInteger count = calls.get(cell);
            return count == null ? 0 : count.get();
        }
    }
}
