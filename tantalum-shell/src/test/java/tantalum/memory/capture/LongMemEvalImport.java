package tantalum.memory.capture;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.ThinkingConfigDisabled;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import tantalum.memory.DailyNotes;
import tantalum.memory.Librarian;
import tantalum.memory.MemoryLimits;
import tantalum.memory.MemoryStore;
import tantalum.memory.WorkingMemory;
import tantalum.memory.ledger.Ledger;
import tantalum.shell.plugin.RunFinishedListener.FinishedRun;
import tantalum.shell.plugin.RunSegment;
import tantalum.shell.plugin.SensitiveText;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * LongMemEval 导入（tier 3，不进 CI）：把每道题的旧对话按原来的日期一轮一轮交给真的记笔记（{@link NoteTaker#note}），
 * 记出这个人的记忆账；每换一天跑一次每天整理（{@link Librarian}）。记笔记的模型用 Claude Haiku 5.5（关思考）。
 * 之后 {@code scenarios/longmemeval.py ask} 把账放进一台真服务器、用 question_date 提问。
 * <p>
 * 是测试代码里的一个 main（surefire 不跑它，一次要一个多小时）：{@code java -cp 测试类路径 tantalum.memory.capture.LongMemEvalImport
 * 题目.json 目录 [同时几道，默认 6]}，由 {@code longmemeval.py build} 调。做完的题有 {@code .done}，再跑跳过。
 * 不覆盖：工具调用（旧对话里没有）、按原话搜（{@code history_search} 要 journal，这里不写会话）。
 */
public final class LongMemEvalImport {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd (EEE) HH:mm", Locale.ENGLISH);
    static final String MODEL = System.getProperty("lme.model", "claude-haiku-5-5");
    private static final Principal USER = new Principal("lme");

    private final AtomicLong input = new AtomicLong();
    private final AtomicLong output = new AtomicLong();
    private final AtomicLong calls = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();

    /** 可以拨的钟：账上的时间是那段对话的时间。 */
    static final class Dial extends Clock {
        private volatile Instant now = Instant.EPOCH;

        void set(Instant at) {
            now = at;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: LongMemEvalImport questions.json out-dir [parallel]");
            System.exit(2);
        }
        new LongMemEvalImport().importAll(Path.of(args[0]), Path.of(args[1]), args.length > 2 ? Integer.parseInt(args[2]) : 6);
        System.exit(0);
    }

    void importAll(Path source, Path target, int parallel) throws Exception {
        JsonNode questions = JSON.readTree(Files.readString(source));
        Path out = Files.createDirectories(target);
        AnthropicClient client = AnthropicOkHttpClient.fromEnv();
        ExecutorService pool = Executors.newFixedThreadPool(parallel);
        List<Future<?>> work = new ArrayList<>();
        for (JsonNode question : questions) {
            work.add(pool.submit(() -> {
                try {
                    importOne(question, out, client);
                } catch (Exception failed) {
                    System.out.println("lme " + question.get("question_id").asString() + " failed: " + failed);
                }
                return null;
            }));
        }
        for (Future<?> each : work) {
            each.get();
        }
        pool.shutdown();
        Files.writeString(out.resolve("usage-" + System.currentTimeMillis() + ".json"), JSON.writeValueAsString(java.util.Map.of(
                "model", MODEL, "calls", calls.get(), "failures", failures.get(), "input", input.get(), "output", output.get())));
        System.out.printf("lme import: %d questions, %d model calls (%d failed), %d input and %d output tokens%n", questions.size(),
                calls.get(), failures.get(), input.get(), output.get());
    }

    private void importOne(JsonNode question, Path out, AnthropicClient client) throws Exception {
        String id = question.get("question_id").asString();
        Path dir = out.resolve(id);
        if (Files.exists(dir.resolve(".done"))) {
            return;
        }
        if (Files.exists(dir)) {
            try (var files = Files.walk(dir)) {
                files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
        Files.createDirectories(dir);
        Dial clock = new Dial();
        Ledger ledger = Ledger.open(dir, clock);
        DailyNotes notes = new DailyNotes(ledger);
        NoteFiler filer = new NoteFiler(new MemoryStore(ledger, MemoryLimits.defaults()), new WorkingMemory(ledger), notes, SensitiveText.current());
        NoteTaker taker = new NoteTaker(new CaptureSettings(true, null, MODEL, null, null, 1, 3), user -> true, user -> notes, user -> filer,
                (user, system, text, maxTokens) -> write(client, system, text, maxTokens), NoteTaker.Catchup.NONE, ZoneOffset.UTC,
                new long[] {2000, 8000, 20000});
        List<Session> sessions = sessions(question);
        LocalDate day = null;
        int noted = 0;
        for (Session session : sessions) {
            LocalDate today = LocalDate.ofInstant(session.at(), ZoneOffset.UTC);
            if (day != null && !today.equals(day)) {
                clock.set(day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().minusSeconds(60));
                new Librarian(ledger, ZoneOffset.UTC).daily();
            }
            day = today;
            for (FinishedRun run : session.runs()) {
                clock.set(run.end());
                if (taker.note(run)) {
                    noted++;
                }
            }
        }
        if (day != null) {
            clock.set(day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().minusSeconds(60));
            new Librarian(ledger, ZoneOffset.UTC).daily();
        }
        taker.close();
        Files.writeString(dir.resolve(".done"), sessions.size() + " sessions, " + noted + " turns noted\n");
        System.out.printf("lme %s: %d sessions, %d turns noted%n", id, sessions.size(), noted);
    }

    record Session(Instant at, List<FinishedRun> runs) {}

    /** 一段对话 → 一轮一个 {@link FinishedRun}：一条用户消息加后面的助手回答；每轮往后挪一分钟。按日期排。 */
    static List<Session> sessions(JsonNode question) {
        List<Session> sessions = new ArrayList<>();
        JsonNode ids = question.get("haystack_session_ids");
        JsonNode dates = question.get("haystack_dates");
        JsonNode bodies = question.get("haystack_sessions");
        for (int i = 0; i < bodies.size(); i++) {
            Instant at = LocalDateTime.parse(dates.get(i).asString(), DATE).toInstant(ZoneOffset.UTC);
            String name = "lme-" + ids.get(i).asString().replaceAll("[^A-Za-z0-9_-]", "-");
            SessionId session = new SessionId(name.length() > 60 ? name.substring(0, 60) : name);
            List<FinishedRun> runs = new ArrayList<>();
            List<RunSegment> segments = new ArrayList<>();
            String title = null;
            int turn = 0;
            for (JsonNode message : bodies.get(i)) {
                String role = message.get("role").asString();
                String text = message.get("content").asString();
                if ("user".equals(role)) {
                    if (!segments.isEmpty()) {
                        runs.add(run(session, ++turn, at, title, segments));
                    }
                    segments = new ArrayList<>();
                    if (title == null) {
                        title = text.strip().replaceAll("\\s+", " ");
                        title = title.length() > 60 ? title.substring(0, 60) : title;
                    }
                    segments.add(new RunSegment(RunSegment.Role.USER, null, null, text, turn + 1 + ".0"));
                } else if (!segments.isEmpty()) {
                    segments.add(new RunSegment(RunSegment.Role.ASSISTANT, null, null, text, turn + 1 + ".1"));
                }
            }
            if (!segments.isEmpty()) {
                runs.add(run(session, ++turn, at, title, segments));
            }
            sessions.add(new Session(at, runs));
        }
        sessions.sort(Comparator.comparing(Session::at));
        return sessions;
    }

    private static FinishedRun run(SessionId session, int turn, Instant start, String title, List<RunSegment> segments) {
        Instant begin = start.plusSeconds(60L * (turn - 1));
        return new FinishedRun(USER, session, new RunId(session.value() + "_" + turn), turn, "COMPLETED", false, begin, begin.plusSeconds(30),
                title == null ? "" : title, "", segments, false);
    }

    private Optional<String> write(AnthropicClient client, String system, String text, int maxTokens) {
        calls.incrementAndGet();
        try {
            Message reply = client.messages().create(MessageCreateParams.builder().model(MODEL).maxTokens(maxTokens).system(system)
                    .thinking(ThinkingConfigDisabled.builder().build()).addUserMessage(text).build());
            input.addAndGet(reply.usage().inputTokens());
            output.addAndGet(reply.usage().outputTokens());
            StringBuilder body = new StringBuilder();
            reply.content().stream().flatMap(block -> block.text().stream()).forEach(block -> body.append(block.text()));
            return Optional.of(body.toString());
        } catch (RuntimeException failed) {
            failures.incrementAndGet();
            System.out.println("lme model call failed: " + failed);
            return Optional.empty();
        }
    }
}
