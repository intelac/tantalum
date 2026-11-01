package tantalum.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.kernel.Kernel;
import tantalum.kernel.Replay;
import tantalum.kernel.hook.HookChain;
import tantalum.kernel.table.BuiltinSteps;
import tantalum.kernel.table.TransitionTable;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.journal.Journal;
import tantalum.shell.web.TestServer;
import tantalum.value.command.CallModel;
import tantalum.value.event.ContextAssembled;
import tantalum.value.event.EventEnvelope;
import tantalum.value.json.Json;
import tantalum.value.model.Prompt;
import tantalum.value.model.ToolCallBlock;

/**
 * 记忆的快照语义（需求 2.3、2.4）：同一运行里刚写的记忆不进本运行后面的 system；下一个运行开始时才进；
 * 回放只用事件里的 system，不读任何记忆文件。
 */
class MemorySnapshotTest {

    private static final String INDEX_LINE = "- [java-version](java-version.md) — user — 回答一律用 Java 25";

    @TempDir
    Path temp;

    private static ToolCallBlock memoryWrite() {
        Map<String, Json> fields = new java.util.LinkedHashMap<>();
        fields.put("name", new Json.Str("java-version"));
        fields.put("type", new Json.Str("user"));
        fields.put("description", new Json.Str("回答一律用 Java 25"));
        fields.put("content", new Json.Str("用户要求示例代码按 Java 25。"));
        return new ToolCallBlock("m1", "memory_write", "{}", new Json.Obj(fields));
    }

    private static void awaitCompleted(TestServer server, String session) throws Exception {
        String runs = "";
        for (int i = 0; i < 1000 && !runs.contains("COMPLETED"); i++) {
            Thread.sleep(10);
            runs = server.get("alice", "/api/sessions/" + session + "/runs").body();
        }
        assertTrue(runs.contains("COMPLETED"), runs);
    }

    @Test
    void newMemoryIsVisibleFromTheNextRunAndReplayNeverReadsFiles() throws Exception {
        Path workspace = Files.createDirectories(temp.resolve("workspace/alice")).getParent();
        Path data = temp.resolve("data");
        ScriptedModelClient model = new ScriptedModelClient(List.of(
                ScriptedModelClient.tools(memoryWrite()),
                ScriptedModelClient.answer("Remembered."),
                ScriptedModelClient.answer("Java 25 it is.")));

        try (TestServer server = new TestServer(data, workspace, model)) {
            server.post("alice", "/api/sessions/s1/submissions",
                    "{\"requestId\":\"r1\",\"mode\":\"prompt\",\"text\":\"remember: always Java 25\"}");
            awaitCompleted(server, "s1");
            assertTrue(Files.isRegularFile(workspace.resolve("alice/memory/java-version.md")), "the tool wrote the file");

            server.post("alice", "/api/sessions/s2/submissions",
                    "{\"requestId\":\"r2\",\"mode\":\"prompt\",\"text\":\"which Java version?\"}");
            awaitCompleted(server, "s2");
        }

        assertEquals(3, model.prompts.size());
        // 同一运行里：第一次调用前没有记忆，写入后第二次调用仍用开始时的快照。
        assertFalse(model.prompts.get(0).system().contains("## Your memory index"));
        assertFalse(model.prompts.get(1).system().contains(INDEX_LINE), "same run keeps the snapshot");
        assertEquals(model.prompts.get(0).system(), model.prompts.get(1).system());
        // 下一个运行开始时读到了索引。
        assertTrue(model.prompts.get(2).system().contains("## Your memory index"), model.prompts.get(2).system());
        assertTrue(model.prompts.get(2).system().endsWith(INDEX_LINE + "\n</memory-index>"), model.prompts.get(2).system());

        List<EventEnvelope> journal;
        try (Journal reopened = Journal.open(data, EventCodec.withBuiltins())) {
            journal = reopened.readAll();
        }
        List<String> systems = journal.stream().filter(envelope -> envelope.event() instanceof ContextAssembled)
                .map(envelope -> ((ContextAssembled) envelope.event()).system()).toList();
        assertEquals(2, systems.size());
        assertTrue(systems.get(1).contains(INDEX_LINE), "the index is in the event, so replay needs no file");

        // 删掉记忆目录再回放：prompt 仍和实时逐个相同。
        try (Stream<Path> files = Files.walk(workspace.resolve("alice/memory"))) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (java.io.IOException failure) {
                    throw new java.io.UncheckedIOException(failure);
                }
            });
        }
        TransitionTable table = new TransitionTable();
        BuiltinSteps.register(table);
        Kernel kernel = new Kernel(table, new HookChain(List.of()), (tool, args) -> true, 10);
        List<Prompt> replayed = Replay.run(kernel, journal).commands().stream()
                .filter(CallModel.class::isInstance).map(command -> ((CallModel) command).prompt()).toList();
        assertEquals(model.prompts, replayed);
    }
}
