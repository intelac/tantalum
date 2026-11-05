package tantalum.shell.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.value.decision.ContextCompacted;
import tantalum.value.decision.StepRecord;
import tantalum.value.event.ContextAssembled;
import tantalum.value.event.EventEnvelope;
import tantalum.value.id.CommandId;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;
import tantalum.value.model.ToolSpec;
import tantalum.value.state.CompactionSettings;
import tantalum.value.state.Compacting;
import tantalum.value.state.ContextSection;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** one-line-journal 需求 3：配上了什么只在变化时记，读回来和原来的事件逐字段相同。 */
class MatchedCodecTest {

    private static final Principal USER = new Principal("alice");
    private static final SessionId SESSION = new SessionId("s-1");
    private static final RunRef RUN = new RunRef(USER, SESSION, new RunId("s-1_0"));
    private static final String SYSTEM = "You are a helpful agent. ".repeat(20);
    private static final ToolSpec GREP = tool("grep", "Search files.");
    private static final ToolSpec FETCH = tool("fetch_url", "Fetch a web page.");
    private static final ToolSpec WRITE = tool("write_file", "Write a file.");
    private static final CompactionSettings SETTINGS = new CompactionSettings(1_000_000, 64_000, 20_000, 32_768, 4_096, 8_192, 500_000, 1.5);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path dir;

    private final EventCodec codec = EventCodec.withBuiltins();
    private long seq;

    /** 参数定义里有整数和小数：冷载入后从文件读出的节点类型和从对象转出的不同，比较时不能当成变了。 */
    private static ToolSpec tool(String name, String description) {
        return new ToolSpec(name, description, new Json.Obj(Map.of("type", new Json.Str("object"),
                "maxItems", new Json.Num(new java.math.BigDecimal("20")), "weight", new Json.Num(new java.math.BigDecimal("0.5")))));
    }

    private static ContextAssembled assembled(String system, List<ToolSpec> tools, List<String> enabled, List<ContextSection> sections) {
        Map<String, String> sources = new java.util.LinkedHashMap<>();
        tools.forEach(tool -> sources.put(tool.name(), tool == FETCH ? "mcp:web" : "builtin"));
        return new ContextAssembled(new CommandId("c-" + System.nanoTime()), system, tools, SETTINGS, sections, enabled, List.of("quota"),
                sources);
    }

    private Line line(ContextAssembled event, boolean compacted) {
        EventEnvelope envelope = new EventEnvelope(seq++, Instant.EPOCH, USER, SESSION, RUN.run(), event);
        List<tantalum.value.decision.Fact> facts = compacted
                ? List.of(new ContextCompacted(RUN, Compacting.Trigger.AUTO, 3, 1, 900, 300, null, false)) : List.of();
        return new Line(envelope, new StepRecord(envelope.seq(), null, List.of(), List.of(), null, null, List.of(), facts));
    }

    /** 写下这些步骤，返回写出的每一行的 payload 和读回来的事件。 */
    private List<JsonNode> write(List<Line> lines) throws Exception {
        try (SessionFiles files = new SessionFiles(new SessionPaths(dir), codec, 4, (p, s, last, offset) -> { })) {
            files.append(USER, SESSION, lines);
        }
        Path file = new SessionPaths(dir).sessionFile(USER, SESSION);
        List<Line> read = new ArrayList<>();
        new SessionFileReader(codec).forEachLine(file, read::add);
        assertEquals(lines.stream().map(Line::envelope).toList(), read.stream().map(Line::envelope).toList(), "every event reads back whole");
        List<JsonNode> payloads = new ArrayList<>();
        for (String text : Files.readAllLines(file).subList(1, lines.size() + 1)) {
            payloads.add(JSON.readTree(text).get("payload"));
        }
        return payloads;
    }

    @Test
    void onlyTheFirstAssemblyIsWrittenInFull() throws Exception {
        List<ContextSection> sections = List.of(new ContextSection("memory-index", "## Your memory index\n- a", true));
        List<JsonNode> payloads = write(List.of(
                line(assembled(SYSTEM, List.of(FETCH, GREP), null, sections), false),
                line(assembled(SYSTEM, List.of(FETCH, GREP), null, sections), false)));

        assertFalse(payloads.get(0).has("diff"));
        assertTrue(payloads.get(0).get("system").asString().startsWith("You are"));
        JsonNode same = payloads.get(1);
        assertTrue(same.get("diff").asBoolean());
        assertEquals(java.util.Set.of("diff", "command", "compaction"), names(same), "nothing changed: only the command and the settings");
    }

    @Test
    void addedRemovedAndChangedToolsCarryOnlyTheirOwnDefinitions() throws Exception {
        ToolSpec newGrep = tool("grep", "Search files, now with globs.");
        List<JsonNode> payloads = write(List.of(
                line(assembled(SYSTEM, List.of(FETCH, GREP), List.of("fetch_url", "grep"), List.of()), false),
                line(assembled(SYSTEM, List.of(GREP), List.of("grep"), List.of()), false),
                line(assembled(SYSTEM, List.of(newGrep, WRITE), List.of("grep", "write_file"), List.of()), false)));

        JsonNode removed = payloads.get(1);
        assertEquals(List.of("grep"), strings(removed.get("toolNames")));
        assertFalse(removed.has("toolSpecs"), "grep is unchanged, fetch_url only drops out");
        assertFalse(removed.has("system"));

        JsonNode changed = payloads.get(2);
        assertEquals(List.of("grep", "write_file"), strings(changed.get("toolNames")));
        assertEquals(List.of("grep", "write_file"), changed.get("toolSpecs").valueStream().map(spec -> spec.get("name").asString()).toList());
    }

    @Test
    void aListThatGoesBackToAllOnIsRemoved() throws Exception {
        List<JsonNode> payloads = write(List.of(
                line(assembled(SYSTEM, List.of(GREP), List.of("grep"), List.of()), false),
                line(assembled(SYSTEM, List.of(GREP), null, List.of()), false)));

        assertTrue(payloads.get(1).get("enabledTools").isNull(), payloads.get(1).toString());
    }

    @Test
    void theAssemblyAfterACompactionIsWrittenInFull() throws Exception {
        List<JsonNode> payloads = write(List.of(
                line(assembled(SYSTEM, List.of(GREP), null, List.of()), false),
                line(assembled(SYSTEM, List.of(GREP), null, List.of()), true),
                line(assembled(SYSTEM, List.of(GREP), null, List.of()), false)));

        assertTrue(payloads.get(1).has("diff"), "the step that compacts still refers to the assembly before it");
        assertFalse(payloads.get(2).has("diff"), "after compacting, the context starts over in full");
    }

    @Test
    void changesWithoutAnEarlierFullContextAreRejected() throws Exception {
        write(List.of(line(assembled(SYSTEM, List.of(GREP), null, List.of()), false),
                line(assembled(SYSTEM, List.of(GREP, WRITE), null, List.of()), false)));
        Path file = new SessionPaths(dir).sessionFile(USER, SESSION);
        List<String> lines = Files.readAllLines(file);
        Files.write(file, List.of(lines.get(0), lines.get(2)));

        SessionCorruptException failure = assertThrows(SessionCorruptException.class,
                () -> new SessionFileReader(codec).forEachLine(file, line -> { }));
        assertTrue(failure.getMessage().contains("without an earlier full context"), failure.getMessage());
    }

    /** 冷载入后接着写：读文件得到的 memo 知道上一次的全文，接着只写变化。 */
    @Test
    void aWriterThatReadTheFileContinuesWithChanges() throws Exception {
        write(List.of(line(assembled(SYSTEM, List.of(GREP), null, List.of()), false)));
        Path file = new SessionPaths(dir).sessionFile(USER, SESSION);
        RecordMemo memo = new SessionFileReader(codec).forEachLine(file, line -> { }).toWriter();
        Line next = line(assembled(SYSTEM, List.of(GREP, WRITE), null, List.of()), false);
        try (SessionFiles files = new SessionFiles(new SessionPaths(dir), codec, 4, (p, s, last, offset) -> { })) {
            files.adopt(USER, SESSION, memo);
            files.append(USER, SESSION, List.of(next));
        }
        List<String> lines = Files.readAllLines(file);
        JsonNode payload = JSON.readTree(lines.getLast()).get("payload");
        assertTrue(payload.has("diff"));
        assertEquals(List.of("write_file"), payload.get("toolSpecs").valueStream().map(spec -> spec.get("name").asString()).toList(),
                "grep did not change, even though it was read back from the file");
        List<Line> read = new ArrayList<>();
        new SessionFileReader(codec).forEachLine(file, read::add);
        assertEquals(next.envelope(), read.getLast().envelope());
    }

    private static java.util.Set<String> names(JsonNode node) {
        java.util.Set<String> names = new java.util.HashSet<>();
        node.properties().forEach(field -> names.add(field.getKey()));
        return names;
    }

    private static List<String> strings(JsonNode array) {
        return array.valueStream().map(JsonNode::asString).toList();
    }
}
