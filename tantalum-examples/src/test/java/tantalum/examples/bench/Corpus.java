package tantalum.examples.bench;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import tantalum.kernel.Kernel;
import tantalum.kernel.compaction.TokenEstimate;
import tantalum.kernel.hook.HookChain;
import tantalum.kernel.table.BuiltinSteps;
import tantalum.kernel.table.TransitionTable;
import tantalum.shell.auth.UserStore;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.store.SessionFile;
import tantalum.shell.store.SessionPaths;
import tantalum.shell.store.StoreFormat;
import tantalum.value.command.AssembleContext;
import tantalum.value.command.CallModel;
import tantalum.value.command.Command;
import tantalum.value.command.RunTool;
import tantalum.value.decision.Decision;
import tantalum.value.event.ContextAssembled;
import tantalum.value.event.Event;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.ModelReplied;
import tantalum.value.event.ToolFinished;
import tantalum.value.event.UserPrompt;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;
import tantalum.value.id.SubmissionId;
import tantalum.value.json.Json;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.ReasoningBlock;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tantalum.value.model.TokenUsage;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolSpec;
import tantalum.value.state.CompactionSettings;
import tantalum.value.state.KernelState;
import tantalum.value.state.OwnerRef;

/**
 * 合成 journal：事件由真实内核逐步推出，所以序列一定合法。多用户、多会话交错，含工具轮和压缩，所有运行都正常结束。
 * 固定用户 {@value #LONG_USER} 有一个 {@value #LONG_PROMPTS} 轮的长会话，{@value #QUIET_USER} 只有一个短会话，用来测“别人的历史多不多”的影响。
 */
public final class Corpus {

    public static final String LONG_USER = "bench-long";
    public static final String QUIET_USER = "bench-quiet";
    public static final String PASSWORD = "bench-password";
    public static final String MANIFEST = "corpus.properties";

    static final int LONG_PROMPTS = 250;
    private static final int PROMPTS_PER_SESSION = 15;
    private static final int ACTIVE_SESSIONS = 20;
    private static final int SESSIONS_PER_USER = 20;
    private static final Instant START = Instant.parse("2026-10-01T00:00:00Z");
    private static final CompactionSettings COMPACTION = new CompactionSettings(32_000, 8_000, 6_000, 4_096, 2_048, 1_024);
    private static final String SYSTEM = "You are a coding agent working in the user's workspace. ".repeat(50);
    private static final List<ToolSpec> TOOLS = List.of(
            new ToolSpec("read_file", "Read a file.", schema()),
            new ToolSpec("grep", "Search files.", schema()));

    /** 生成结果。{@code longRun} 和 {@code quietRun} 是相应会话最后一个运行，用来测 {@code /turns}、{@code /trace}。 */
    public record Manifest(long events, int sessions, String longSession, String longRun, String quietSession, String quietRun) {}

    private record Pending(SessionId session, Principal principal, RunId run, Event event) {}

    private static final int FLUSH_EVENTS = 20_000;

    private final Random random;
    private final Kernel kernel;
    private final SessionPaths paths;
    private final EventCodec codec = EventCodec.withBuiltins();
    private final java.util.Map<SessionId, List<EventEnvelope>> buffered = new java.util.LinkedHashMap<>();
    private int bufferedEvents;
    private KernelState state = KernelState.empty();
    private final List<Pending> pending = new ArrayList<>();
    private final Deque<SessionPlan> waiting = new ArrayDeque<>();
    private long seq;
    private long ids;
    private int active;
    private String longRun;
    private String quietRun;

    private static final class SessionPlan {
        final Principal principal;
        final SessionId session;
        int prompts;

        SessionPlan(String principal, String session, int prompts) {
            this.principal = new Principal(principal);
            this.session = new SessionId(session);
            this.prompts = prompts;
        }
    }

    private Corpus(SessionPaths paths, long seed) {
        this.paths = paths;
        this.random = new Random(seed);
        TransitionTable table = new TransitionTable();
        BuiltinSteps.register(table);
        this.kernel = new Kernel(table, new HookChain(List.of()), (tool, args) -> true, 10);
    }

    /** 写到一个空目录。事件数略超 {@code targetEvents}：到数后不再开新轮，把进行中的走完，并走完长会话。 */
    public static Manifest generate(Path dataDir, int targetEvents, long seed) throws IOException, SQLException {
        Manifest manifest = new Corpus(new SessionPaths(dataDir), seed).run(targetEvents);
        StoreFormat.check(dataDir);
        writeManifest(dataDir, manifest);
        try (UserStore users = UserStore.open(dataDir)) {
            users.add(LONG_USER, PASSWORD);
            users.add(QUIET_USER, PASSWORD);
        }
        return manifest;
    }

    private Manifest run(int targetEvents) throws IOException {
        SessionPlan longPlan = new SessionPlan(LONG_USER, "long-session", LONG_PROMPTS);
        SessionPlan quietPlan = new SessionPlan(QUIET_USER, "quiet-session", 3);
        waiting.add(longPlan);
        waiting.add(quietPlan);
        int sessions = 2;
        int perSessionEvents = 90;
        int otherSessions = Math.max(10, targetEvents / perSessionEvents);
        for (int i = 0; i < otherSessions; i++) {
            waiting.add(new SessionPlan("user-" + i / SESSIONS_PER_USER, "s-" + i, PROMPTS_PER_SESSION));
            sessions++;
        }
        fill(targetEvents);
        while (!pending.isEmpty()) {
            int pick = random.nextInt(pending.size());
            Pending next = pending.get(pick);
            pending.set(pick, pending.getLast());
            pending.removeLast();
            feed(next);
            if (state.sessions().get(next.session()).activeRun() == null) {
                finishedTurn(next.session(), longPlan, quietPlan, targetEvents);
                fill(targetEvents);
            }
        }
        flush();
        return new Manifest(seq, sessions, longPlan.session.value(), longRun, quietPlan.session.value(), quietRun);
    }

    private final java.util.Map<SessionId, SessionPlan> running = new java.util.HashMap<>();

    private void fill(int targetEvents) {
        while (active < ACTIVE_SESSIONS && !waiting.isEmpty() && (seq < targetEvents || waiting.peek().prompts == LONG_PROMPTS)) {
            SessionPlan plan = waiting.poll();
            running.put(plan.session, plan);
            active++;
            startPrompt(plan);
        }
    }

    private void finishedTurn(SessionId session, SessionPlan longPlan, SessionPlan quietPlan, int targetEvents) {
        SessionPlan plan = running.get(session);
        RunId last = state.runs().values().stream()
                .filter(run -> run.ref().session().equals(session))
                .map(run -> run.ref().run())
                .max(java.util.Comparator.comparingLong(tantalum.value.id.RunId::seq))
                .orElseThrow();
        if (plan == longPlan) {
            longRun = last.value();
        } else if (plan == quietPlan) {
            quietRun = last.value();
        }
        boolean mustFinish = plan == longPlan || plan == quietPlan;
        if (plan.prompts > 0 && (mustFinish || seq < targetEvents)) {
            startPrompt(plan);
        } else {
            running.remove(session);
            active--;
        }
    }

    private void startPrompt(SessionPlan plan) {
        plan.prompts--;
        long id = ids++;
        pending.add(new Pending(plan.session, plan.principal, null, new UserPrompt(new SubmissionId("sub-" + id),
                new RequestId("req-" + id), new OwnerRef.OfSession(plan.session), words(40 + random.nextInt(400)))));
    }

    /** 攒一批再写：每事件 fsync 时，生成几十万事件要几十分钟。 */
    private void flush() throws IOException {
        for (List<EventEnvelope> events : buffered.values()) {
            EventEnvelope first = events.getFirst();
            try (SessionFile file = SessionFile.open(paths, first.principal(), first.session(), codec)) {
                file.append(events);
            }
        }
        buffered.clear();
        bufferedEvents = 0;
    }

    private void feed(Pending next) throws IOException {
        EventEnvelope envelope = new EventEnvelope(seq, START.plusSeconds(seq * 3), next.principal(), next.session(), next.run(), next.event());
        seq++;
        buffered.computeIfAbsent(next.session(), ignored -> new ArrayList<>()).add(envelope);
        if (++bufferedEvents >= FLUSH_EVENTS) {
            flush();
        }
        Decision decision = kernel.step(state, envelope);
        state = decision.newState();
        for (Command command : decision.commands()) {
            pending.add(new Pending(next.session(), next.principal(), command.run().run(), resultOf(command)));
        }
    }

    private Event resultOf(Command command) {
        return switch (command) {
            case AssembleContext assemble -> new ContextAssembled(assemble.id(), SYSTEM, TOOLS, null, COMPACTION);
            case CallModel call -> reply(call);
            case RunTool tool -> new ToolFinished(tool.id(), tool.call().id(), words(200 + random.nextInt(3800)), false);
            default -> throw new IllegalStateException("corpus does not produce " + command);
        };
    }

    private ModelReplied reply(CallModel call) {
        // 压缩阈值以上报的用量为准，所以按 prompt 的实际大小上报，和真实服务一样。
        long prompt = TokenEstimate.ofText(call.prompt().system()) + TokenEstimate.ofTools(call.prompt().tools())
                + TokenEstimate.ofMessages(call.prompt().messages());
        TokenUsage usage = new TokenUsage(prompt, 50 + random.nextInt(500), 0, 0);
        List<ContentBlock> content = new ArrayList<>();
        content.add(new ReasoningBlock("anthropic", new Json.Obj(Map.of("type", new Json.Str("thinking"),
                "thinking", new Json.Str(words(100 + random.nextInt(600))), "signature", new Json.Str(words(200))))));
        if (call.prompt().output().toolsAllowed() && random.nextInt(100) < 60) {
            int calls = 1 + random.nextInt(3);
            for (int i = 0; i < calls; i++) {
                String path = "src/file" + random.nextInt(50) + ".java";
                content.add(new ToolCallBlock("call-" + ids++, "read_file", "{\"path\":\"" + path + "\"}",
                        new Json.Obj(Map.of("path", new Json.Str(path)))));
            }
            return new ModelReplied(call.id(), content, StopReason.TOOL_USE, "bench", usage);
        }
        content.add(new TextBlock(words(100 + random.nextInt(1200))));
        return new ModelReplied(call.id(), content, StopReason.END_TURN, "bench", usage);
    }

    private String words(int length) {
        StringBuilder text = new StringBuilder(length + 8);
        while (text.length() < length) {
            text.append(Integer.toString(random.nextInt(1 << 20), 36)).append(' ');
        }
        return text.toString();
    }

    private static Json schema() {
        return new Json.Obj(Map.of("type", new Json.Str("object")));
    }

    private static void writeManifest(Path dir, Manifest manifest) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("events", Long.toString(manifest.events()));
        properties.setProperty("sessions", Integer.toString(manifest.sessions()));
        properties.setProperty("longSession", manifest.longSession());
        properties.setProperty("longRun", manifest.longRun());
        properties.setProperty("quietSession", manifest.quietSession());
        properties.setProperty("quietRun", manifest.quietRun());
        try (OutputStream out = Files.newOutputStream(dir.resolve(MANIFEST))) {
            properties.store(out, "bench corpus");
        }
    }

    public static Manifest readManifest(Path dir) throws IOException {
        Properties properties = new Properties();
        try (var in = Files.newInputStream(dir.resolve(MANIFEST))) {
            properties.load(in);
        }
        return new Manifest(Long.parseLong(properties.getProperty("events")), Integer.parseInt(properties.getProperty("sessions")),
                properties.getProperty("longSession"), properties.getProperty("longRun"),
                properties.getProperty("quietSession"), properties.getProperty("quietRun"));
    }
}
