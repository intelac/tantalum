package tantalum.shell.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.boot.Engine;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.journal.SessionFileReader;
import tantalum.shell.journal.SessionPaths;
import tantalum.shell.loop.Snapshots;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.ModelOutcome;
import tantalum.shell.store.RecordReplay;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.SessionId;
import tantalum.value.model.Prompt;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;

/**
 * journal-snapshots 需求 1、2，走真的服务器：门槛很小时，说几句话账里就有了快照；每份快照都和它前面的步一致；
 * 重启以后会话从最后一份快照载入，对话、运行和重启前一样，接着说话照常。
 */
class SnapshotRestartTest {

    private static final Principal ALICE = new Principal("alice");
    private static final SessionId S1 = new SessionId("s1");

    @TempDir
    Path temp;

    private final List<Prompt> prompts = new CopyOnWriteArrayList<>();
    private final ModelClient model = (prompt, chunks) -> {
        prompts.add(prompt);
        return new ModelOutcome.Replied(List.of(new TextBlock("reply " + prompts.size() + " " + "x".repeat(300))), StopReason.END_TURN, "m", null);
    };

    @AfterEach
    void forgetTheThreshold() {
        System.clearProperty("tantalum.journal.snapshot-bytes");
    }

    private TestServer server() throws Exception {
        Files.createDirectories(temp.resolve("workspace"));
        return new TestServer(temp.resolve("data"), temp.resolve("workspace"), model);
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 1000 && !condition.getAsBoolean(); i++) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), what);
    }

    private static void prompt(Engine engine, String text) throws InterruptedException {
        var before = engine.state().sessions().get(S1);
        var previous = before == null ? null : before.lastRun();
        assertEquals(200, engine.intake().submit(ALICE, S1, new RequestId(UUID.randomUUID().toString()),
                tantalum.value.event.Delivery.QUEUE, text).status());
        await("the run ended", () -> {
            var current = engine.state().sessions().get(S1);
            return current != null && current.lastRun() != null && !current.lastRun().equals(previous) && current.activeRun() == null;
        });
    }

    private Path file() {
        return new SessionPaths(temp.resolve("data")).sessionFile(ALICE, S1);
    }

    private long snapshotLines() throws Exception {
        return Files.readAllLines(file(), StandardCharsets.UTF_8).stream().filter(line -> line.startsWith("{\"snapshot\":")).count();
    }

    @Test
    void aLongSessionGetsSnapshotsAndLoadsFromTheLastOneAfterARestart() throws Exception {
        System.setProperty("tantalum.journal.snapshot-bytes", "2000");
        String messages;
        String runs;
        try (TestServer server = server()) {
            Engine engine = server.context.getBean(Engine.class);
            // 下一份快照要等写过两倍于上一份快照的字节。快照里有整份历史和会话上下文（system 和工具定义，这里约 45 KB），
            // 所以要多说几句才有第二份
            for (int i = 0; i < 60; i++) {
                prompt(engine, "question " + i + " " + "q".repeat(200));
            }
            await("written", () -> {
                try {
                    return snapshotLines() > 0;
                } catch (Exception notYet) {
                    return false;
                }
            });
            messages = server.get("alice", "/api/sessions/s1/messages").body();
            runs = server.get("alice", "/api/sessions/s1/runs").body();
        }
        assertTrue(snapshotLines() >= 2, "several snapshots: " + snapshotLines());
        RecordReplay records = new RecordReplay(new SessionPaths(temp.resolve("data")), new SessionFileReader(EventCodec.withBuiltins()),
                new Snapshots(EventCodec.withBuiltins().mapper()));
        assertEquals(OptionalLong.empty(), records.firstWrongSnapshot(ALICE, S1), "every snapshot matches the steps before it");
        assertTrue(records.load(ALICE, S1, Long.MAX_VALUE).orElseThrow().from() > 0, "loading starts at the last snapshot");

        System.clearProperty("tantalum.journal.snapshot-bytes");
        try (TestServer server = server()) {
            assertEquals(messages, server.get("alice", "/api/sessions/s1/messages").body());
            assertEquals(runs, server.get("alice", "/api/sessions/s1/runs").body());
            int before = prompts.size();
            prompt(server.context.getBean(Engine.class), "after the restart");
            assertEquals(before + 1, prompts.size());
            // 模型看到的历史是完整的：重启前的 60 问 60 答都在
            assertEquals(121, prompts.getLast().messages().size(), prompts.getLast().messages().toString());
        }
        assertEquals(OptionalLong.empty(), records.firstWrongSnapshot(ALICE, S1));
    }

    /** 半截快照（崩溃时只写了一部分）就是残缺的尾部：读的人不认它，下次打开写入时截掉，接着写。 */
    @Test
    void aHalfWrittenSnapshotIsJustABrokenTail() throws Exception {
        System.setProperty("tantalum.journal.snapshot-bytes", "2000");
        try (TestServer server = server()) {
            Engine engine = server.context.getBean(Engine.class);
            for (int i = 0; i < 3; i++) {
                prompt(engine, "question " + i + " " + "q".repeat(200));
            }
        }
        Files.writeString(file(), "{\"snapshot\":99,\"state\":{\"id\"", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        System.clearProperty("tantalum.journal.snapshot-bytes");
        try (TestServer server = server()) {
            assertTrue(server.get("alice", "/api/sessions/s1/messages").body().contains("question 2"));
            prompt(server.context.getBean(Engine.class), "after the crash");
        }
        assertTrue(Files.readString(file()).contains("after the crash"));
        assertTrue(!Files.readString(file()).contains("{\"snapshot\":99"), "the broken tail was cut");
    }
}
