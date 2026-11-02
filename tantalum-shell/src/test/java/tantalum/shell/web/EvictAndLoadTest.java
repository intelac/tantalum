package tantalum.shell.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.boot.Engine;
import tantalum.shell.journal.SessionPaths;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.ModelOutcome;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.SessionId;
import tantalum.value.model.Prompt;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;

/** 真实管线：运行结束后会话离开内存，再提交时透明载入，模型看到的和从未回收时相同。 */
class EvictAndLoadTest {

    private static final Principal ALICE = new Principal("alice");
    private static final SessionId S1 = new SessionId("s1");
    private static final SessionId S2 = new SessionId("s2");
    private static final String[] ALWAYS_EVICT = {
        "--tantalum.residency.idle=0s", "--tantalum.residency.max-resident=1000",
        "--tantalum.residency.snapshot-events=1", "--tantalum.residency.sweep-interval=0s"
    };

    @TempDir
    Path temp;

    private final List<Prompt> prompts = new CopyOnWriteArrayList<>();
    private final ModelClient model = (prompt, chunks) -> {
        prompts.add(prompt);
        return new ModelOutcome.Replied(List.of(new TextBlock("reply " + prompts.size())), StopReason.END_TURN, "m", null);
    };

    private TestServer server(String name, String... extra) throws Exception {
        Files.createDirectories(temp.resolve(name + "-workspace"));
        return new TestServer(temp.resolve(name), temp.resolve(name + "-workspace"), model, extra);
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 1000 && !condition.getAsBoolean(); i++) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), what);
    }

    private static Engine.Submission submit(Engine engine, SessionId session, String text) {
        return engine.submit(ALICE, session, new RequestId(UUID.randomUUID().toString()), "follow-up", text);
    }

    private List<Prompt> conversation(String name, boolean evicts, String... extra) throws Exception {
        prompts.clear();
        try (TestServer server = server(name, extra)) {
            Engine engine = server.context.getBean(Engine.class);
            for (int turn = 0; turn < 3; turn++) {
                int before = prompts.size();
                assertEquals(200, submit(engine, S1, "turn " + turn).status());
                await("model called", () -> prompts.size() == before + 1);
                await("run ended", () -> evicts ? !engine.state().sessions().containsKey(S1)
                        : engine.state().sessions().get(S1).activeRun() == null);
            }
        }
        return new ArrayList<>(prompts);
    }

    @Test
    void theModelSeesTheSameConversationAsWithoutEviction() throws Exception {
        List<Prompt> never = conversation("never", false);
        List<Prompt> always = conversation("always", true, ALWAYS_EVICT);
        assertEquals(3, never.size());
        assertEquals(never, always);
        assertEquals(5, always.get(2).messages().size(), "two earlier turns plus the new prompt");
    }

    @Test
    void anEvictedSessionHasASnapshotAndLeavesNoRunBehind() throws Exception {
        conversation("snapshots", true, ALWAYS_EVICT);
        Path snapshot = new SessionPaths(temp.resolve("snapshots")).snapshotFile(ALICE, S1);
        assertTrue(Files.exists(snapshot), "written before leaving memory");
    }

    /** 文件中间坏了：这个会话拒绝，其他会话照常。坏事件之后不会被当成新会话从头开始。 */
    @Test
    void aSessionThatCannotBeLoadedIsRefusedWithoutHurtingOthers() throws Exception {
        try (TestServer server = server("corrupt", ALWAYS_EVICT)) {
            Engine engine = server.context.getBean(Engine.class);
            assertEquals(200, submit(engine, S1, "first").status());
            await("evicted", () -> prompts.size() == 1 && !engine.state().sessions().containsKey(S1));

            SessionPaths paths = new SessionPaths(temp.resolve("corrupt"));
            await("snapshot written", () -> Files.exists(paths.snapshotFile(ALICE, S1)));
            Thread.sleep(100);
            Files.delete(paths.snapshotFile(ALICE, S1));
            Path file = paths.sessionFile(ALICE, S1);
            List<String> lines = new ArrayList<>(Files.readAllLines(file));
            lines.set(2, "not json");
            Files.write(file, lines);

            assertEquals(200, submit(engine, S1, "second").status(), "the failure is only known after the event is durable");
            await("refused", () -> submit(engine, S1, "third").status() == 500);
            assertFalse(engine.state().sessions().containsKey(S1), "must not start over as a new session");

            assertEquals(200, submit(engine, S2, "other").status());
            await("other session works", () -> prompts.size() == 2);
            assertEquals(1, prompts.get(1).messages().size(), "the other session's history is its own");
        }
    }
}
