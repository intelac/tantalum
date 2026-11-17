package tantalum.shell.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.value.decision.StepRecord;
import tantalum.value.event.ContextAssembled;
import tantalum.value.event.EventEnvelope;
import tantalum.value.id.CommandId;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;
import tantalum.value.state.SessionContext;
import tools.jackson.databind.JsonNode;
import tantalum.value.state.SessionContextFixture;

/**
 * plugin-settings 需求 5.5：加上插件参数之后，没记参数的会话写出来和原来逐字节相同。
 * 前两条在加字段之前就通过，之后每一步都要保持。
 */
class PluginParamsCodecTest {

    /** 加字段之前写出的一行组装上下文的载荷。 */
    private static final String OLD_EVENT = "{\"command\":{\"value\":\"c-1\"},\"system\":\"system\",\"tools\":[],\"sections\":[],"
            + "\"enabledTools\":[\"grep\"],\"enabledPlugins\":[\"quota\"]}";
    /** 加字段之前写出的会话状态里的上下文。 */
    private static final String OLD_CONTEXT = "{\"system\":\"system\",\"tools\":[],\"sections\":[],\"initialTools\":[\"grep\"],"
            + "\"activeTools\":[\"grep\"],\"activePlugins\":[\"quota\"]}";

    private final EventCodec codec = EventCodec.withBuiltins();

    @Test
    void anEventWithoutParamsIsWrittenAsBefore() {
        ContextAssembled event = new ContextAssembled(new CommandId("c-1"), "system", List.of(), null, List.of(),
                List.of("grep"), List.of("quota"), Map.of(), null);
        assertEquals(OLD_EVENT, codec.encode(event));
        assertEquals(OLD_EVENT, codec.encode(codec.decode(ContextAssembled.TYPE, 2, OLD_EVENT)));
    }

    @Test
    void aContextWithoutParamsIsWrittenAsBefore() {
        SessionContext context = new SessionContext("system", List.of(), null, List.of(), List.of("grep"), List.of("grep"),
                List.of("quota"));
        assertEquals(OLD_CONTEXT, codec.mapper().writeValueAsString(context));
        SessionContext read = codec.mapper().readValue(OLD_CONTEXT, SessionContext.class);
        assertEquals(context, read);
        assertEquals(OLD_CONTEXT, codec.mapper().writeValueAsString(read));
    }

    /** 整个旧会话：83 次组装、83 次上下文变化，读进来再写出去一个字节都不变。 */
    @Test
    void anOldSessionIsRewrittenByteForByte(@TempDir Path in, @TempDir Path out) throws Exception {
        byte[] original;
        try (InputStream stream = getClass().getResourceAsStream("/warmup/session.jsonl")) {
            original = stream.readAllBytes();
        }
        String text = new String(original, StandardCharsets.UTF_8);
        assertTrue(text.contains("\"type\":\"ContextAssembled\""), "the old session assembles contexts");
        assertTrue(text.contains("\"context\":{"), "the old session changes its frozen context");
        JsonNode header = codec.mapper().readTree(text.substring(0, text.indexOf('\n')));
        Principal principal = new Principal(header.get("principal").asString());
        SessionId session = new SessionId(header.get("session").asString());
        Path file = new SessionPaths(in).sessionFile(principal, session);
        Files.createDirectories(file.getParent());
        Files.write(file, original);

        List<Line> lines = new ArrayList<>();
        new SessionFileReader(codec).forEachLine(file, lines::add);
        SessionPaths rewritten = new SessionPaths(out);
        try (SessionFiles files = new SessionFiles(rewritten, codec, 4, (p, s, seq, offset) -> { })) {
            files.append(principal, session, lines);
        }
        assertEquals(text, Files.readString(rewritten.sessionFile(principal, session)));
    }

    // ---- 任务 16：带参数的组装 ----

    private static final Principal USER = new Principal("alice");
    private static final SessionId SESSION = new SessionId("s-1");

    /** 插件按名字排，键按声明的顺序（这里故意不是字母序）。 */
    private static Json.Obj params(long tokens) {
        Map<String, Json> guard = new LinkedHashMap<>();
        guard.put("tools", new Json.Arr(List.of(new Json.Str("write_file"))));
        guard.put("maxChars", new Json.Num(BigDecimal.valueOf(1000000)));
        Map<String, Json> all = new LinkedHashMap<>();
        all.put("guard", new Json.Obj(guard));
        all.put("quota", new Json.Obj(Map.of("tokens", new Json.Num(BigDecimal.valueOf(tokens)))));
        return new Json.Obj(all);
    }

    private static ContextAssembled assembled(Json.Obj params) {
        return new ContextAssembled(new CommandId("c-1"), "system", List.of(), null, List.of(), List.of("grep"), List.of("quota"),
                Map.of(), null, params);
    }

    private static Line line(long seq, ContextAssembled event) {
        return new Line(new EventEnvelope(seq, Instant.EPOCH, USER, SESSION, RunId.of(SESSION, 0), event),
                new StepRecord(seq, null, List.of(), List.of(), null, null, List.of(), List.of()));
    }

    @Test
    void paramsReadBackEqualAndInTheSameOrder() {
        ContextAssembled event = assembled(params(500));
        String written = codec.encode(event);
        assertTrue(written.endsWith(",\"pluginParams\":{\"guard\":{\"tools\":[\"write_file\"],\"maxChars\":1000000},"
                + "\"quota\":{\"tokens\":500}}}"), written);
        ContextAssembled read = (ContextAssembled) codec.decode(ContextAssembled.TYPE, 2, written);
        // 读回来的和现算的相等（数字也是）：内核靠这个判断参数变没变
        assertEquals(event, read);
        assertEquals(written, codec.encode(read));

        SessionContext context = SessionContextFixture.of("system", List.of(), null, List.of()).withPluginParams(params(500));
        String frozen = codec.mapper().writeValueAsString(context);
        SessionContext back = codec.mapper().readValue(frozen, SessionContext.class);
        assertEquals(context, back);
        assertEquals(frozen, codec.mapper().writeValueAsString(back));
    }

    /** 参数没变不重复写；变了只写这一个字段；没了写成 null，读的时候去掉。 */
    @Test
    void paramsInAnAssembledContextAreWrittenOnlyWhenTheyChange() throws Exception {
        LineCodec lines = new LineCodec(codec);
        RecordMemo memo = RecordMemo.writer(USER, SESSION);
        List<ContextAssembled> events = List.of(assembled(params(500)), assembled(params(500)), assembled(params(9)), assembled(null),
                assembled(null));
        List<String> written = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            written.add(lines.encode(line(i + 1, events.get(i)), memo));
        }
        assertTrue(written.get(0).contains("\"pluginParams\":{"), written.get(0));
        JsonNode same = codec.mapper().readTree(written.get(1)).get("payload");
        assertTrue(same.path("diff").asBoolean(), written.get(1));
        assertFalse(same.has("pluginParams"), "unchanged params are not repeated: " + written.get(1));
        JsonNode changed = codec.mapper().readTree(written.get(2)).get("payload");
        assertEquals(9, changed.path("pluginParams").path("quota").path("tokens").asInt(), written.get(2));
        assertEquals(List.of("diff", "command", "pluginParams"), List.copyOf(changed.propertyNames()), "nothing else is written");
        assertTrue(codec.mapper().readTree(written.get(3)).get("payload").path("pluginParams").isNull(), written.get(3));
        assertFalse(written.get(4).contains("pluginParams"), written.get(4));

        RecordMemo reader = RecordMemo.reader(USER, SESSION);
        List<ContextAssembled> read = new ArrayList<>();
        for (String text : written) {
            read.add((ContextAssembled) lines.decode(lines.parse(text), USER, SESSION, reader).envelope().event());
        }
        assertEquals(events, read);
    }
}
