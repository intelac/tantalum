package tantalum.shell.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tantalum.shell.conversation.TranscriptFixture.call;
import static tantalum.shell.conversation.TranscriptFixture.claudeThinking;
import static tantalum.shell.conversation.TranscriptFixture.openAiThinking;
import static tantalum.shell.conversation.TranscriptFixture.redacted;
import static tantalum.shell.conversation.TranscriptFixture.text;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.kernel.hook.KernelHook;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.journal.RecordCodec;
import tantalum.shell.journal.ResultWriter;
import tantalum.shell.journal.SessionFileReader;
import tantalum.shell.journal.SessionFiles;
import tantalum.shell.journal.SessionPaths;
import tantalum.shell.loop.SessionSlice;
import tantalum.shell.sse.SseHub;
import tantalum.shell.sse.SseMessage;
import tantalum.shell.store.IndexWriter;
import tantalum.shell.store.RecordReplay;
import tantalum.shell.store.SessionIndex;
import tantalum.shell.store.SessionReplay;
import tantalum.shell.store.TracePage;
import tantalum.shell.store.TraceQuery;
import tantalum.shell.store.UsageLedger;
import tantalum.shell.trace.Trace;
import tantalum.shell.trace.TraceJson;
import tantalum.shell.trace.TraceRecord;
import tantalum.value.state.OwnerRef;
import tantalum.value.event.UserPrompt;
import tantalum.value.event.FollowUpSubmitted;
import tantalum.value.attachment.ImageRef;
import tantalum.value.attachment.FileRef;
import tantalum.value.command.DoWork;
import tantalum.value.decision.Decision;
import tantalum.value.decision.Fact;
import tantalum.value.event.CompactRequested;
import tantalum.value.event.ContextAssembled;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.ModelFailed;
import tantalum.value.event.ModelReplied;
import tantalum.value.event.RunInterrupted;
import tantalum.value.event.SteerSubmitted;
import tantalum.value.event.TimerFired;
import tantalum.value.event.ToolFinished;
import tantalum.value.event.WorkDone;
import tantalum.value.hook.Allow;
import tantalum.value.hook.HookContext;
import tantalum.value.hook.HookResult;
import tantalum.value.hook.NeedWork;
import tantalum.value.hook.PromptPatch;
import tantalum.value.hook.Stop;
import tantalum.value.id.CommandId;
import tantalum.value.id.RequestId;
import tantalum.value.id.RunId;
import tantalum.value.id.SubmissionId;
import tantalum.value.json.Json;
import tantalum.value.model.FailureReason;
import tantalum.value.model.Message;
import tantalum.value.model.RequestOptions;
import tantalum.value.model.StopReason;
import tantalum.value.model.TokenUsage;
import tantalum.value.model.ToolSpec;
import tantalum.value.state.CompactionSettings;
import tantalum.value.state.ContextSection;
import tantalum.value.state.KernelState;
import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * 浏览器看到的 JSON：{@code /messages} 的条目和 SSE 消息。改序列化代码时这些必须逐字节不变
 * （SSE 事实消息的键顺序不是约定，按键排序后比较）。有意改格式时用 {@code -Dwire.record=true} 重新生成期望文件，并在提交里说明。
 */
class WireFormatTest {

    private static final DefaultPrettyPrinter LF_PRETTY =
            new DefaultPrettyPrinter().withObjectIndenter(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE.withLinefeed("\n"));

    private static final Path EXPECTED = Path.of("src/test/resources/wire");
    private static final CompactionSettings SETTINGS = new CompactionSettings(1000, 200, 70, 60, 30, 500);
    private static final JsonMapper SORTED = JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

    private final EventCodec codec = EventCodec.withBuiltins();
    private final TranscriptFixture fx = new TranscriptFixture();

    private static String block(String tag) {
        return (tag + " ").repeat(45).substring(0, 90);
    }

    private RunId nextRun(String text) {
        fx.feed(null, fx.prompt(text));
        RunId run = RunId.of(TranscriptFixture.SESSION, fx.lastSeq());
        fx.feed(run, new ContextAssembled(fx.lastCommands().getFirst().id(), "sys", List.of(), SETTINGS, List.of()));
        return run;
    }

    /** 每种条目和每种事实都出现：工具轮、三种思考、自动压缩、失败的运行。 */
    private void scenario() {
        RunId first = fx.start(block("q1"), SETTINGS);
        fx.feed(first, fx.reply(claudeThinking("plan"), call("c1", "read_file")));
        fx.feed(first, fx.finished(first, "c1", "file body", false));
        fx.feed(first, fx.reply(new TokenUsage(700, 10, 0, 0), openAiThinking("check"), redacted(), text(block("a1"))));
        RunId second = nextRun(block("q2"));
        fx.feed(second, fx.reply(new TokenUsage(790, 10, 0, 0), text(block("a2"))));
        RunId third = nextRun(block("q3"));
        fx.feed(third, fx.reply(new TokenUsage(50, 20, 0, 0), claudeThinking("summarizing"), text("Goal: q")));
        fx.feed(third, new ContextAssembled(fx.lastCommands().getFirst().id(), "sys", List.of(), SETTINGS, List.of()));
        fx.feed(third, fx.reply(text("a3")));
        RunId refused = nextRun("q4");
        fx.tick(Duration.ofSeconds(30));
        fx.feed(refused, new ModelFailed(fx.callModel(), FailureReason.REFUSAL, false, null, "", "refused", null, null, null));
    }

    private static void check(String name, String actual) throws IOException {
        Path file = EXPECTED.resolve(name);
        if (Boolean.getBoolean("wire.record")) {
            Files.createDirectories(EXPECTED);
            Files.writeString(file, actual, StandardCharsets.UTF_8);
        }
        assertEquals(Files.readString(file, StandardCharsets.UTF_8), actual, name + " changed; see the class comment");
    }

    @Test
    void itemsAsTheMessagesApiReturnsThem() throws IOException {
        scenario();
        StringBuilder out = new StringBuilder();
        for (TranscriptItem item : fx.items()) {
            out.append(codec.mapper().writeValueAsString(ItemJson.toMap(codec.mapper(), item))).append('\n');
        }
        check("items.jsonl", out.toString());
    }

    @Test
    void sseMessagesForItemsAndFacts() throws IOException {
        scenario();
        StringBuilder out = new StringBuilder();
        for (TranscriptFixture.Change change : fx.changes) {
            SseMessage message = SseHub.itemMessage(codec.mapper(), change.seq(), change.index(), change.item());
            out.append(message.id()).append(' ').append(message.event()).append(' ').append(message.data()).append('\n');
        }
        for (int i = 0; i < fx.decisions.size(); i++) {
            Decision decision = fx.decisions.get(i);
            long seq = fx.envelopes.get(i).seq();
            int index = 0;
            for (Fact fact : decision.facts()) {
                SseMessage message = SseHub.factMessage(codec.mapper(), seq, index++, fact);
                JsonNode data = SORTED.readTree(message.data());
                out.append(message.id()).append(' ').append(message.event()).append(' ').append(SORTED.writeValueAsString(data)).append('\n');
            }
        }
        check("sse.txt", out.toString());
    }

    // ---- 轨迹：trace.json（整页）、trace-sse.txt（推送），以及“实时 = 回放” ----

    private static final ToolSpec READ = new ToolSpec("read_file", "Read a file", new Json.Obj(Map.of()));
    private static final RequestOptions OPTIONS = new RequestOptions("claude", "claude-x", 8192, true,
            new Json.Obj(new java.util.LinkedHashMap<>(Map.of("thinking", new Json.Str("adaptive")))));

    /** 模型调用前给 system 补一段、加一个工具（补丁）。 */
    private static final KernelHook STYLE = new KernelHook() {
        @Override public String name() { return "style"; }
        @Override public HookResult beforeModelCall(KernelState state, HookContext context) {
            return new Allow(new PromptPatch("\nFollow the house style.", List.of(new ToolSpec("style_check", "Check style", new Json.Obj(Map.of())))));
        }
    };

    /** 第一次模型调用前要一轮工作，结果作为续写文字 "cited" 交给模型（要工作）。 */
    private static final KernelHook CITE = new KernelHook() {
        @Override public String name() { return "cite"; }
        @Override public HookResult beforeModelCall(KernelState state, HookContext context) {
            boolean cited = state.sessions().get(context.run().session()).messages().contains(Message.user("cited"));
            return cited ? Allow.PLAIN : new NeedWork("lookup", new Json.Str("q1"));
        }
    };

    /** 拦下 rm（拦工具）。 */
    private static final KernelHook GUARD = new KernelHook() {
        @Override public String name() { return "guard"; }
        @Override public HookResult beforeToolCall(KernelState state, HookContext context) {
            return context.call().name().equals("rm") ? new Stop("destructive") : Allow.PLAIN;
        }
    };

    /** 在重试等待里插话之后的那一步：实时投影在这里被回收、再从会话文件冷载入。 */
    private int coldLoadAt;

    private static ContextSection memory(String body) {
        return new ContextSection("memory", body, true);
    }

    private static RunId prompt(TranscriptFixture fx, String text, String system, List<ContextSection> sections) {
        fx.feed(null, fx.prompt(text));
        RunId run = RunId.of(TranscriptFixture.SESSION, fx.lastSeq());
        assembled(fx, run, system, sections);
        return run;
    }

    private static void assembled(TranscriptFixture fx, RunId run, String system, List<ContextSection> sections) {
        fx.feed(run, new ContextAssembled(fx.lastCommands().getFirst().id(), system, List.of(READ), SETTINGS, sections));
    }

    private static ToolFinished startedFinish(TranscriptFixture fx, RunId run, String call, String text, boolean error) {
        ToolFinished finished = fx.finished(run, call, text, error);
        return new ToolFinished(finished.command(), finished.toolCallId(), text, error, fx.now().minusMillis(400));
    }

    /**
     * 每种轨迹记录都出现：插件钩子（补丁、拦工具、要工作和续写）、一次 429 重试（等待时插话）、并行工具和 MCP 工具、上下文更新、
     * 自动压缩后 system 更新、手动压缩（轮次之间），最后一轮工具运行时重启。
     */
    private TranscriptFixture traceScenario() {
        TranscriptFixture fx = new TranscriptFixture(true, List.of(STYLE, CITE, GUARD));
        RunId first = prompt(fx, block("q1"), "sys", List.of(memory("a")));
        DoWork work = (DoWork) fx.lastCommands().getFirst();
        fx.feed(first, new WorkDone(work.id(), work.workType(), new Json.Str("source: a.md"), false, "cited", fx.now().minusMillis(800)));
        fx.feed(first, new ModelFailed(fx.callModel(), FailureReason.RATE_LIMITED, true, Duration.ofSeconds(2), "", "HTTP 429: slow down",
                fx.now().minusMillis(900), null, OPTIONS));
        CommandId timer = fx.lastCommands().getFirst().id();
        fx.feed(first, new SteerSubmitted(new SubmissionId("steer"), new RequestId("steer"), "keep it short"));
        coldLoadAt = fx.envelopes.size();
        fx.feed(first, new TimerFired(timer));
        fx.feed(first, new ModelReplied(fx.callModel(), List.of(claudeThinking("plan"), call("c1", "read_file"),
                call("c2", "mcp__github__search"), call("c3", "rm")), StopReason.TOOL_USE, "claude-x-1", new TokenUsage(300, 40, 100, 0),
                fx.now().minusMillis(900), fx.now().minusMillis(300), OPTIONS));
        fx.feed(first, startedFinish(fx, first, "c2", "no match", true));
        fx.feed(first, startedFinish(fx, first, "c1", "file body", false));
        fx.feed(first, fx.reply(new TokenUsage(700, 10, 0, 0), text(block("a1"))));
        RunId second = prompt(fx, block("q2"), "sys", List.of(memory("a\nb")));
        fx.feed(second, fx.reply(new TokenUsage(790, 10, 0, 0), text(block("a2"))));
        RunId third = prompt(fx, block("q3"), "sys", List.of(memory("a\nb")));
        fx.feed(third, fx.reply(new TokenUsage(50, 20, 0, 0), text("Goal: q")));
        assembled(fx, third, "sys v2", List.of(memory("a\nb")));
        fx.feed(third, fx.reply(text(block("a3"))));
        RunId fourth = prompt(fx, block("q4"), "sys v2", List.of(memory("a\nb")));
        fx.feed(fourth, fx.reply(text(block("a4"))));
        fx.feed(null, new CompactRequested(new SubmissionId("compact"), new RequestId("compact"), null));
        RunId manual = RunId.of(TranscriptFixture.SESSION, fx.lastSeq());
        fx.feed(manual, fx.reply(new TokenUsage(60, 20, 0, 0), text("Goal: r")));
        RunId last = prompt(fx, "q5", "sys v2", List.of(memory("a\nb")));
        fx.feed(last, fx.reply(call("c9", "read_file")));
        fx.feed(last, new RunInterrupted());
        return fx;
    }

    /** 不中断的实时推送：每个事件先事实、再条目、最后轨迹记录，下标连续，和 Cleanroom 一样。 */
    private List<SseMessage> livePushes(TranscriptFixture fx) {
        Trace trace = new Trace();
        List<SseMessage> pushes = new ArrayList<>();
        for (int i = 0; i < fx.envelopes.size(); i++) {
            EventEnvelope envelope = fx.envelopes.get(i);
            long items = fx.changes.stream().filter(change -> change.seq() == envelope.seq()).count();
            int index = fx.decisions.get(i).facts().size() + (int) items;
            for (TraceRecord record : trace.apply(envelope, fx.befores.get(i), fx.decisions.get(i))) {
                pushes.add(SseHub.recordMessage(codec.mapper(), envelope.seq(), index++, record));
            }
        }
        return pushes;
    }

    /** 把场景像线上一样写成会话文件（事件行加结果行），用服务里的 {@link RecordReplay} 和 {@link TraceQuery} 读。 */
    private <T> T withJournal(TranscriptFixture fx, Path dir, JournalUse<T> use) throws Exception {
        SessionPaths paths = new SessionPaths(dir);
        try (ResultWriter journal = new ResultWriter(paths, codec, fx.kernel())) {
            for (EventEnvelope envelope : fx.envelopes) {
                journal.write(envelope);
            }
        }
        return use.apply(new RecordReplay(paths, new SessionFileReader(codec)), paths);
    }

    @FunctionalInterface
    private interface JournalUse<T> {
        T apply(RecordReplay replay, SessionPaths paths) throws Exception;
    }

    private String pageJson(TracePage page) {
        // 换行一律 \n：Jackson 默认的缩进用系统的换行，Windows 上是 \r\n，期望文件是 LF（windows-server N9）
        return codec.mapper().writer().with(LF_PRETTY).writeValueAsString(page.toMap(codec.mapper())) + "\n";
    }

    @Test
    void traceAsTheTraceApiReturnsIt(@TempDir Path dir) throws Exception {
        TranscriptFixture fx = traceScenario();
        TracePage page = withJournal(fx, dir, (replay, paths) -> new TraceQuery(paths, replay, new RecordCodec(codec))
                .page(TranscriptFixture.USER, TranscriptFixture.SESSION, null).orElseThrow());
        assertEquals(java.util.Set.of("run", "system", "user", "context", "compacted", "request", "tool", "work", "hook"),
                page.records().stream().map(record -> TraceJson.toMap(codec.mapper(), record).get("kind")).collect(java.util.stream.Collectors.toSet()));
        check("trace.json", pageJson(page));
    }

    @Test
    void traceSseMessages() throws IOException {
        StringBuilder out = new StringBuilder();
        for (SseMessage message : livePushes(traceScenario())) {
            out.append(message.id()).append(' ').append(message.event()).append(' ').append(message.data()).append('\n');
        }
        check("trace-sse.txt", out.toString());
    }

    /**
     * 需求 9.2：实时推送（中途回收、再从会话文件冷载入）按 ID 合并后，和重放会话文件得到的页面逐字节相同。
     * 合并照客户端的做法：每条记录留最后一次推送的版本，按 {@code pos} 排序。
     */
    @Test
    void livePushesMergeIntoExactlyTheReplayedPage(@TempDir Path dir) throws Exception {
        TranscriptFixture fx = traceScenario();
        withJournal(fx, dir, (replay, paths) -> {
            Map<String, TraceRecord> merged = new java.util.LinkedHashMap<>();
            Map<String, String> pushed = new java.util.HashMap<>();
            Trace live = new Trace();
            for (int i = 0; i < fx.envelopes.size(); i++) {
                if (i == coldLoadAt) {
                    SessionSlice slice = replay.load(TranscriptFixture.USER, TranscriptFixture.SESSION, fx.envelopes.get(i).seq()).orElseThrow().slice();
                    live = new Trace();
                    live.restore(TranscriptFixture.SESSION, slice.trace());
                }
                for (TraceRecord record : live.apply(fx.envelopes.get(i), fx.befores.get(i), fx.decisions.get(i))) {
                    merged.put(record.id(), record);
                    pushed.put(record.id(), codec.mapper().writeValueAsString(TraceJson.toMap(codec.mapper(), record)));
                }
            }
            TracePage page = new TraceQuery(paths, replay, new RecordCodec(codec)).page(TranscriptFixture.USER, TranscriptFixture.SESSION, null).orElseThrow();
            List<String> fromPushes = TracePage.of(fx.lastSeq(), merged.values(), null).records().stream().map(record -> pushed.get(record.id())).toList();
            List<String> fromReplay = page.records().stream()
                    .map(record -> codec.mapper().writeValueAsString(TraceJson.toMap(codec.mapper(), record))).toList();
            assertEquals(String.join("\n", fromReplay), String.join("\n", fromPushes));
            assertEquals(fx.lastSeq(), page.seq());
            assertTrue(coldLoadAt > 0 && merged.values().stream().anyMatch(record -> record.id().startsWith("u:")
                    && record.head().step() != null && record.head().step() == 1 && record instanceof TraceRecord.UserInput user
                    && user.mode() == TraceRecord.UserInput.Mode.STEER), "the steer moved to the retried step across the cold load");
            return null;
        });
    }

    // ---- 附件（attachments 任务 11）：attachments.txt，另查“实时 = 回放” ----

    /** 字节数故意大到单独就超出 20 MiB 的图片预算（base64 后；真实的存储版本不超过 4 MiB），每个请求都省略它，请求记录据此带 omittedImages。 */
    // ---- 会话中途换工具（core-tools 任务 21）：wire/tool-change.txt ----

    private static final ToolSpec WRITE = new ToolSpec("write_file", "Write a file", new Json.Obj(Map.of()));

    /** 用户开着的工具名单随组装一起记下；全集不变。 */
    private static void assembledWith(TranscriptFixture fx, RunId run, List<String> enabled) {
        fx.feed(run, new ContextAssembled(fx.lastCommands().getFirst().id(), "sys", List.of(READ, WRITE), null, List.of(), enabled,
                List.of()));
    }

    /** 第一问 write_file 没开；用户在插件页打开它；第二问开头追加工具变化，模型调用 write_file。 */
    private TranscriptFixture toolChangeScenario() {
        TranscriptFixture fx = new TranscriptFixture(true, List.of());
        fx.feed(null, fx.prompt("summarise a.txt"));
        RunId first = RunId.of(TranscriptFixture.SESSION, fx.lastSeq());
        assembledWith(fx, first, List.of("read_file"));
        fx.feed(first, fx.reply(text("it says hello")));
        fx.feed(null, fx.prompt("now write that to b.txt"));
        RunId second = RunId.of(TranscriptFixture.SESSION, fx.lastSeq());
        assembledWith(fx, second, List.of("read_file", "write_file"));
        fx.feed(second, fx.reply(TranscriptFixture.call("w1", "write_file")));
        fx.feed(second, fx.finished(second, "w1", "Created b.txt (5 bytes).", false));
        fx.feed(second, fx.reply(text("done")));
        return fx;
    }

    @Test
    void toolChangesInItemsPushesTraceAndPrompts(@TempDir Path dir) throws Exception {
        TranscriptFixture fx = toolChangeScenario();
        StringBuilder out = new StringBuilder("# items\n");
        for (TranscriptItem item : fx.items()) {
            out.append(codec.mapper().writeValueAsString(ItemJson.toMap(codec.mapper(), item))).append('\n');
        }
        out.append("# item pushes\n");
        for (TranscriptFixture.Change change : fx.changes) {
            SseMessage message = SseHub.itemMessage(codec.mapper(), change.seq(), change.index(), change.item());
            out.append(message.id()).append(' ').append(message.event()).append(' ').append(message.data()).append('\n');
        }
        out.append("# prompts\n");
        for (var decision : fx.decisions) {
            for (var command : decision.commands()) {
                if (command instanceof tantalum.value.command.CallModel call) {
                    out.append(codec.mapper().writeValueAsString(call.prompt())).append('\n');
                }
            }
        }
        TracePage page = withJournal(fx, dir, (replay, paths) -> new TraceQuery(paths, replay, new RecordCodec(codec))
                .page(TranscriptFixture.USER, TranscriptFixture.SESSION, null).orElseThrow());
        out.append("# trace\n").append(pageJson(page));
        check("tool-change.txt", out.toString());
    }

    private static final ImageRef PHOTO = new ImageRef("sha256:" + "a".repeat(64), "photo.jpg", "image/jpeg", 16_000_000, 2048, 1536, 4000, 3000);
    private static final FileRef REPORT = new FileRef("sha256:" + "b".repeat(64), "report.pdf", "application/pdf", 90_000, 3);
    private static final FileRef NOTES = new FileRef("sha256:" + "c".repeat(64), "notes.txt", "text/plain", 12, null);
    private static final ImageRef SKETCH = new ImageRef("sha256:" + "d".repeat(64), "sketch.png", "image/png", 2_000, 40, 30);

    /** 不压缩：图片和 PDF 的估算远超上面那组设置的窗口。 */
    private static void assembledWithoutCompaction(TranscriptFixture fx, RunId run) {
        fx.feed(run, new ContextAssembled(fx.lastCommands().getFirst().id(), "sys", List.of(READ), null, List.of()));
    }

    /** 提问带图片和 PDF；运行中插话带文件；排队的提交只有图片、没有文字，运行结束后开下一轮。 */
    private TranscriptFixture attachmentScenario() {
        TranscriptFixture fx = new TranscriptFixture(true, List.of());
        fx.feed(null, new UserPrompt(new SubmissionId("p"), new RequestId("p"), new OwnerRef.OfSession(TranscriptFixture.SESSION),
                "what is this?", List.of(PHOTO, REPORT)));
        RunId first = RunId.of(TranscriptFixture.SESSION, fx.lastSeq());
        assembledWithoutCompaction(fx, first);
        fx.feed(first, new SteerSubmitted(new SubmissionId("s"), new RequestId("s"), "also this", List.of(NOTES)));
        fx.feed(null, new FollowUpSubmitted(new SubmissionId("f"), new RequestId("f"), "", List.of(SKETCH)));
        fx.feed(first, fx.reply(text("a photo and a report")));
        fx.feed(first, fx.reply(text("and the notes"))); // 插话让运行多调一次模型，这次才结束
        RunId second = RunId.of(TranscriptFixture.SESSION, fx.lastSeq());
        assembledWithoutCompaction(fx, second);
        fx.feed(second, fx.reply(text("a sketch")));
        return fx;
    }

    @Test
    void attachmentsInItemsPushesAndTrace(@TempDir Path dir) throws Exception {
        TranscriptFixture fx = attachmentScenario();
        StringBuilder out = new StringBuilder("# items\n");
        for (TranscriptItem item : fx.items()) {
            out.append(codec.mapper().writeValueAsString(ItemJson.toMap(codec.mapper(), item))).append('\n');
        }
        out.append("# item pushes\n");
        for (TranscriptFixture.Change change : fx.changes) {
            SseMessage message = SseHub.itemMessage(codec.mapper(), change.seq(), change.index(), change.item());
            out.append(message.id()).append(' ').append(message.event()).append(' ').append(message.data()).append('\n');
        }
        TracePage page = withJournal(fx, dir, (replay, paths) -> new TraceQuery(paths, replay, new RecordCodec(codec))
                .page(TranscriptFixture.USER, TranscriptFixture.SESSION, null).orElseThrow());
        out.append("# trace\n").append(pageJson(page));
        check("attachments.txt", out.toString());
    }

    @Test
    void attachmentRecordsPushedLiveEqualTheReplayedPage(@TempDir Path dir) throws Exception {
        TranscriptFixture fx = attachmentScenario();
        Map<String, String> pushed = new java.util.LinkedHashMap<>();
        Map<String, TraceRecord> merged = new java.util.LinkedHashMap<>();
        for (SseMessage message : livePushes(fx)) {
            JsonNode data = codec.mapper().readTree(message.data());
            pushed.put(data.get("id").asString(), message.data());
        }
        Trace live = new Trace();
        for (int i = 0; i < fx.envelopes.size(); i++) {
            for (TraceRecord record : live.apply(fx.envelopes.get(i), fx.befores.get(i), fx.decisions.get(i))) {
                merged.put(record.id(), record);
            }
        }
        withJournal(fx, dir, (replay, paths) -> {
            TracePage page = new TraceQuery(paths, replay, new RecordCodec(codec)).page(TranscriptFixture.USER, TranscriptFixture.SESSION, null).orElseThrow();
            List<String> fromReplay = page.records().stream()
                    .map(record -> codec.mapper().writeValueAsString(TraceJson.toMap(codec.mapper(), record))).toList();
            List<String> fromPushes = TracePage.of(fx.lastSeq(), merged.values(), null).records().stream()
                    .map(record -> pushed.get(record.id())).toList();
            assertEquals(String.join("\n", fromReplay), String.join("\n", fromPushes));
            assertEquals(3, page.records().stream().filter(record -> record instanceof TraceRecord.UserInput user
                    && !user.attachments().isEmpty()).count());
            return null;
        });
    }
}
