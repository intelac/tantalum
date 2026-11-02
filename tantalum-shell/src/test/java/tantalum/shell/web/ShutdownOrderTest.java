package tantalum.shell.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.boot.Engine;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.journal.SessionPaths;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.ModelOutcome;
import tantalum.shell.store.SessionStore;
import tantalum.value.event.UserPrompt;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.SessionId;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;

/** 任务 39（需求 7.1–7.3、H8）：关闭不丢已接受的提交，卡住的模型调用拖不过上限，空闲会话写快照，关闭后拒绝提交。 */
class ShutdownOrderTest {

    private static final Principal ALICE = new Principal("alice");
    private static final SessionId S1 = new SessionId("s1");
    private static final String WAIT = "--tantalum.shutdown-wait=1s";

    @TempDir
    Path temp;

    private final AtomicInteger calls = new AtomicInteger();
    private final CountDownLatch release = new CountDownLatch(1);

    private TestServer server(ModelClient model) throws Exception {
        Files.createDirectories(temp.resolve("workspace"));
        return new TestServer(temp.resolve("data"), temp.resolve("workspace"), model, WAIT);
    }

    /** 一直等，直到被中断。 */
    private final ModelClient hanging = (prompt, chunks) -> {
        calls.incrementAndGet();
        try {
            Thread.sleep(Long.MAX_VALUE);
        } catch (InterruptedException interrupted) {
            return new ModelOutcome.Interrupted("partial");
        }
        throw new AssertionError("unreachable");
    };

    /** 吞掉中断，直到测试结束才返回：模拟不响应中断的客户端。 */
    private final ModelClient stubborn = (prompt, chunks) -> {
        calls.incrementAndGet();
        while (true) {
            try {
                release.await();
                return new ModelOutcome.Replied(List.of(new TextBlock("late")), StopReason.END_TURN, "m", null);
            } catch (InterruptedException ignored) {
                // 不理会。
            }
        }
    };

    private static void awaitCalls(AtomicInteger calls) throws InterruptedException {
        for (int i = 0; i < 500 && calls.get() == 0; i++) {
            Thread.sleep(10);
        }
        assertEquals(1, calls.get());
    }

    @Test
    void aHangingModelCallIsInterruptedInTimeAndTheAcceptedSubmissionSurvives() throws Exception {
        Engine engine;
        long took;
        try (TestServer server = server(hanging)) {
            engine = server.context.getBean(Engine.class);
            assertEquals(200, engine.intake().submit(ALICE, S1, new RequestId("r1"), "prompt", "hi").status());
            awaitCalls(calls);
            long start = System.nanoTime();
            server.context.close();
            took = (System.nanoTime() - start) / 1_000_000;
        }
        assertTrue(took < 1_000 + 3_000, "closing took " + took + " ms with a 1 s shutdown wait");
        assertEquals(1, calls.get(), "no new model call was started while closing");
        assertEquals(503, engine.intake().submit(ALICE, S1, new RequestId("r2"), "prompt", "after").status());
        assertTrue(new SessionStore(temp.resolve("data"), EventCodec.withBuiltins()).readAll().stream()
                .anyMatch(envelope -> envelope.event() instanceof UserPrompt prompt && prompt.text().equals("hi")));

        AtomicInteger again = new AtomicInteger();
        try (TestServer restarted = server((prompt, chunks) -> {
            again.incrementAndGet();
            return new ModelOutcome.Replied(List.of(new TextBlock("ok")), StopReason.END_TURN, "m", null);
        })) {
            String runs = restarted.get("alice", "/api/sessions/s1/runs").body();
            assertTrue(runs.contains("CANCELLED"), runs);
            assertEquals(0, again.get(), "the interrupted call is not made again");
        }
    }

    /** H8：等待期内能结束的调用，结果要落盘；否则重启后这次已经花了钱的回复就丢了。 */
    @Test
    void aCallThatFinishesWithinTheWaitIsKept() throws Exception {
        try (TestServer server = server((prompt, chunks) -> {
            calls.incrementAndGet();
            try {
                Thread.sleep(300);
            } catch (InterruptedException interrupted) {
                return new ModelOutcome.Interrupted("");
            }
            return new ModelOutcome.Replied(List.of(new TextBlock("finished during shutdown")), StopReason.END_TURN, "m", null);
        })) {
            Engine engine = server.context.getBean(Engine.class);
            assertEquals(200, engine.intake().submit(ALICE, S1, new RequestId("r1"), "prompt", "hi").status());
            awaitCalls(calls);
            server.context.close();
        }
        try (TestServer restarted = server((prompt, chunks) -> {
            throw new AssertionError("must not be called");
        })) {
            String runs = restarted.get("alice", "/api/sessions/s1/runs").body();
            assertTrue(runs.contains("COMPLETED"), runs);
            assertTrue(restarted.get("alice", "/api/sessions/s1/messages").body().contains("finished during shutdown"));
        }
    }

    @Test
    void aModelCallThatIgnoresInterruptsCannotHoldTheShutdown() throws Exception {
        try (TestServer server = server(stubborn)) {
            Engine engine = server.context.getBean(Engine.class);
            assertEquals(200, engine.intake().submit(ALICE, S1, new RequestId("r1"), "prompt", "hi").status());
            awaitCalls(calls);
            long start = System.nanoTime();
            server.context.close();
            long took = (System.nanoTime() - start) / 1_000_000;
            assertTrue(took < 1_000 + 2_000 + 3_000, "closing took " + took + " ms");
        } finally {
            release.countDown();
        }
    }

    @Test
    void idleSessionsGetASnapshotOnClose() throws Exception {
        try (TestServer server = server((prompt, chunks) ->
                new ModelOutcome.Replied(List.of(new TextBlock("ok")), StopReason.END_TURN, "m", null))) {
            Engine engine = server.context.getBean(Engine.class);
            assertEquals(200, engine.intake().submit(ALICE, S1, new RequestId("r1"), "prompt", "hi").status());
            for (int i = 0; i < 500 && (engine.state().sessions().get(S1) == null || engine.state().sessions().get(S1).activeRun() != null); i++) {
                Thread.sleep(10);
            }
            Path snapshot = new SessionPaths(temp.resolve("data")).snapshotFile(ALICE, S1);
            assertTrue(Files.notExists(snapshot), "a short run does not reach the run-end snapshot threshold");
            server.context.close();
            assertTrue(Files.exists(snapshot), "closing writes the projection cache");
        }
    }
}
