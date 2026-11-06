package tantalum.shell.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.value.command.AssembleContext;
import tantalum.value.command.CallModel;
import tantalum.value.decision.SessionChange;
import tantalum.value.decision.StepRecord;
import tantalum.value.event.ContextAssembled;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.FollowUpSubmitted;
import tantalum.value.event.UserPrompt;
import tantalum.value.id.CommandId;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;
import tantalum.value.id.SubmissionId;
import tantalum.value.json.Json;
import tantalum.value.model.Message;
import tantalum.value.model.ModelChangeBlock;
import tantalum.value.model.ModelChoice;
import tantalum.value.model.Prompt;
import tantalum.value.model.ReasoningBlock;
import tantalum.value.model.Role;
import tantalum.value.model.TextBlock;
import tantalum.value.state.OwnerRef;
import tantalum.value.state.RunState;
import tools.jackson.databind.JsonNode;

/**
 * model-picker 任务 1（需求 7.1、7.4）：记录选择的字段写了能读回；为空时写出的行和改动前逐字节相同；
 * 组装上下文里的选择只在变化时写，请求里的选择只写相对上一次的变化。
 */
class ModelChoiceCodecTest {

    private static final Principal USER = new Principal("alice");
    private static final SessionId SESSION = new SessionId("s-1");
    private static final RunRef RUN = new RunRef(USER, SESSION, RunId.of(SESSION, 0));
    private static final ModelChoice SONNET = new ModelChoice("claude", "claude-sonnet-5-5", "medium");
    private static final ModelChoice QWEN = new ModelChoice("local", "qwen3.8", "on");

    @TempDir
    Path dir;

    private final EventCodec codec = EventCodec.withBuiltins();

    @Test
    void everyNewFieldReadsBack() {
        UserPrompt prompt = new UserPrompt(new SubmissionId("s"), new RequestId("r"), new OwnerRef.OfSession(SESSION), "hi",
                List.of(), QWEN);
        assertEquals(prompt, codec.decode(UserPrompt.TYPE, 1, codec.encode(prompt)));
        FollowUpSubmitted followUp = new FollowUpSubmitted(new SubmissionId("s"), new RequestId("r"), "next", List.of(), SONNET);
        assertEquals(followUp, codec.decode(FollowUpSubmitted.TYPE, 1, codec.encode(followUp)));
        ContextAssembled context = assembled(QWEN);
        assertEquals(context, codec.decode(ContextAssembled.TYPE, 2, codec.encode(context)));

        // 推理块记下是谁写的，换模型的说明是一种内容块，都在结果行里经过引用和展开
        Message reply = new Message(Role.ASSISTANT, List.of(new ReasoningBlock("openai", new Json.Str("thinking ".repeat(20)),
                "local", "qwen3.8"), new TextBlock("ok")), false);
        Message change = new Message(Role.SYSTEM, List.of(new ModelChangeBlock(SONNET.label(), QWEN.label())), false);
        RunState run = RunState.start(RUN, new OwnerRef.OfSession(SESSION)).withModel(QWEN, SONNET);
        StepRecord record = new StepRecord(0, new SessionChange(SESSION, USER, true, List.of(change, reply), null, null, null, null, null,
                null, null, null, null, new SessionChange.Changed<>(QWEN), new SessionChange.Changed<>(SONNET)),
                List.of(run), List.of(), null, null,
                List.of(new AssembleContext(new CommandId("c-0"), RUN, QWEN, SONNET),
                        new CallModel(new CommandId("c-1"), RUN, new Prompt("system", List.of(), List.of(change, reply)).withModel(QWEN))),
                List.of());
        RecordCodec records = new RecordCodec(codec);
        String line = records.encode(record, RecordMemo.writer(USER, SESSION));
        assertTrue(line.contains("\"kind\":\"modelChange\""), line);
        assertEquals(record, records.decode(line, RecordMemo.reader(USER, SESSION)).record());
    }

    @Test
    void withoutAChoiceTheOldSessionIsWrittenByteForByte(@TempDir Path out) throws Exception {
        byte[] original;
        try (InputStream in = getClass().getResourceAsStream("/warmup/session.jsonl")) {
            original = in.readAllBytes();
        }
        String text = new String(original, StandardCharsets.UTF_8);
        JsonNode header = codec.mapper().readTree(text.substring(0, text.indexOf('\n')));
        Principal principal = new Principal(header.get("principal").asString());
        SessionId session = new SessionId(header.get("session").asString());
        SessionPaths paths = new SessionPaths(dir);
        Path file = paths.sessionFile(principal, session);
        Files.createDirectories(file.getParent());
        Files.write(file, original);

        List<Line> lines = new ArrayList<>();
        new SessionFileReader(codec).forEachLine(file, lines::add);
        SessionPaths rewritten = new SessionPaths(out);
        try (SessionFiles files = new SessionFiles(rewritten, codec, 4, (p, s, seq, offset) -> { })) {
            files.append(principal, session, lines);
        }
        assertEquals(text, Files.readString(rewritten.sessionFile(principal, session)),
                "reading an old session and writing it again with this version changes no byte");
    }

    @Test
    void theChoiceInAnAssembledContextIsWrittenOnlyWhenItChanges() throws Exception {
        LineCodec lines = new LineCodec(codec);
        RecordMemo memo = RecordMemo.writer(USER, SESSION);
        lines.encode(line(1, assembled(SONNET)), memo);
        String same = lines.encode(line(2, assembled(SONNET)), memo);
        String changed = lines.encode(line(3, assembled(QWEN)), memo);
        JsonNode samePayload = codec.mapper().readTree(same).get("payload");
        assertTrue(samePayload.path("diff").asBoolean(), same);
        assertFalse(samePayload.has("model"), "an unchanged choice is not repeated: " + same);
        JsonNode changedPayload = codec.mapper().readTree(changed).get("payload");
        assertEquals("local", changedPayload.path("model").path("route").asString(), changed);

        RecordMemo reader = RecordMemo.reader(USER, SESSION);
        List<ModelChoice> read = new ArrayList<>();
        for (String written : List.of(lines.encode(line(1, assembled(SONNET)), RecordMemo.writer(USER, SESSION)))) {
            read.add(((ContextAssembled) lines.decode(lines.parse(written), USER, SESSION, reader).envelope().event()).model());
        }
        read.add(((ContextAssembled) lines.decode(lines.parse(same), USER, SESSION, reader).envelope().event()).model());
        read.add(((ContextAssembled) lines.decode(lines.parse(changed), USER, SESSION, reader).envelope().event()).model());
        assertEquals(List.of(SONNET, SONNET, QWEN), read);
    }

    @Test
    void aRequestWritesItsChoiceOnlyWhenItDiffersFromTheLastRequest() {
        RecordCodec records = new RecordCodec(codec);
        RecordMemo writer = RecordMemo.writer(USER, SESSION);
        List<String> written = new ArrayList<>();
        Prompt base = new Prompt("system", List.of(), List.of(Message.user("q")));
        List<Prompt> prompts = List.of(base, base.withModel(SONNET), base.withModel(SONNET), base.withModel(QWEN), base);
        for (int i = 0; i < prompts.size(); i++) {
            written.add(records.encode(call(i, prompts.get(i)), writer));
        }
        assertFalse(written.get(0).contains("\"model\""), "no choice, nothing written: " + written.get(0));
        assertTrue(written.get(1).contains("\"model\":{"), written.get(1));
        assertFalse(written.get(2).contains("\"model\""), "same as the last request: " + written.get(2));
        assertTrue(written.get(3).contains("\"model\":{"), written.get(3));
        assertTrue(written.get(4).contains("\"model\":null"), "back to no choice is written as null: " + written.get(4));

        RecordMemo reader = RecordMemo.reader(USER, SESSION);
        List<ModelChoice> read = new ArrayList<>();
        for (String line : written) {
            read.add(((CallModel) records.decode(line, reader).record().commands().getFirst()).prompt().model());
        }
        assertEquals(java.util.Arrays.asList(null, SONNET, SONNET, QWEN, null), read);
        assertNull(read.getLast());
    }

    private static StepRecord call(long seq, Prompt prompt) {
        return new StepRecord(seq, null, List.of(), List.of(), null, null,
                List.of(new CallModel(new CommandId("c-" + seq), RUN, prompt)), List.of());
    }

    private static ContextAssembled assembled(ModelChoice model) {
        return new ContextAssembled(new CommandId("c"), "system", List.of(), null, List.of(), null, null, Map.of(), model);
    }

    private static Line line(long seq, ContextAssembled event) {
        return new Line(new EventEnvelope(seq, Instant.EPOCH, USER, SESSION, RUN.run(), event),
                new StepRecord(seq, null, List.of(), List.of(), null, null, List.of(), List.of()));
    }
}
