package tantalum.shell.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.journal.SessionPaths;
import tantalum.shell.loop.SessionSlice;
import tantalum.shell.sse.SseHub;
import tantalum.shell.sse.SseMessage;
import tantalum.shell.store.SessionSnapshot;
import tantalum.shell.store.SnapshotStore;
import tantalum.value.decision.Decision;
import tantalum.value.decision.Fact;
import tantalum.value.event.ContextAssembled;
import tantalum.value.event.ModelFailed;
import tantalum.value.id.RunId;
import tantalum.value.model.FailureReason;
import tantalum.value.model.TokenUsage;
import tantalum.value.state.CompactionSettings;
import tantalum.value.state.KernelState;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * 浏览器和磁盘看到的 JSON：{@code /messages} 的条目、SSE 消息、会话快照文件。改序列化代码时这些必须逐字节不变
 * （SSE 事实消息的键顺序不是约定，按键排序后比较）。有意改格式时用 {@code -Dwire.record=true} 重新生成期望文件，并在提交里说明。
 */
class WireFormatTest {

    private static final Path EXPECTED = Path.of("src/test/resources/wire");
    private static final CompactionSettings SETTINGS = new CompactionSettings(1000, 200, 70, 60, 30, 500);
    private static final JsonMapper SORTED = JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

    private final EventCodec codec = EventCodec.withBuiltins();
    private final TranscriptFixture fx = new TranscriptFixture();

    @TempDir
    Path temp;

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
        fx.feed(refused, new ModelFailed(fx.callModel(), FailureReason.REFUSAL, false, null, "", "refused"));
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

    @Test
    void sessionSnapshotFile() throws IOException {
        scenario();
        KernelState state = fx.state();
        SessionPaths paths = new SessionPaths(temp);
        new SnapshotStore(paths, codec).write(TranscriptFixture.USER, new SessionSnapshot(new SessionSlice(
                state.sessions().get(TranscriptFixture.SESSION),
                List.copyOf(state.runs().values().stream().sorted(java.util.Comparator.comparing(run -> run.ref().run().value())).toList()),
                fx.transcript.export(TranscriptFixture.SESSION), fx.lastSeq()), 1234));
        check("snapshot.json", Files.readString(paths.snapshotFile(TranscriptFixture.USER, TranscriptFixture.SESSION), StandardCharsets.UTF_8));
    }
}
