package tantalum.shell.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.kernel.Kernel;
import tantalum.kernel.hook.HookChain;
import tantalum.kernel.step.BuiltinSteps;
import tantalum.kernel.table.TransitionTable;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.ModelOutcome;
import tantalum.shell.web.TestServer;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.UserPrompt;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.SessionId;
import tantalum.value.id.SubmissionId;
import tantalum.value.state.EndReason;
import tantalum.value.state.Ended;
import tantalum.value.state.KernelState;
import tantalum.value.state.OwnerRef;
import tantalum.value.state.RunState;

/** 需求 5 第 7 条：三种背压。无论哪种都不能阻塞环。 */
class BackpressureTest {

    @TempDir
    Path temp;

    private static Kernel kernel() {
        TransitionTable table = new TransitionTable();
        BuiltinSteps.register(table);
        return new Kernel(table, new HookChain(List.of()), (tool, args) -> false, 10);
    }

    private static EventEnvelope prompt(int i) {
        SessionId session = new SessionId("s" + i);
        return new EventEnvelope(i, Instant.EPOCH, new Principal("u"), session, null,
                new UserPrompt(new SubmissionId("sub" + i), new RequestId("req" + i), new OwnerRef.OfSession(session), "hi"));
    }

    @Test
    void fullOutputRingFailsOnlyTheAffectedRunAndNeverBlocks() throws Exception {
        CountDownLatch stuck = new CountDownLatch(1);
        OutputRing output = new OutputRing(2, (slot, sequence, end) -> stuck.await());
        output.start();
        InputRing input = LoopFixtures.inputRing(64, (slot, sequence, end) -> { });
        input.start();
        StateView view = new StateView();
        ResultPublisher results = new ResultPublisher(input, new InFlightCommands(), Clock.systemUTC(), 0);
        Cleanroom cleanroom = LoopFixtures.cleanroom(kernel(), () -> false, output::tryPublish, LoopFixtures.NO_PROJECTION,
                (seq, index, fact) -> { }, view, results);

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            for (int i = 0; i < 8; i++) {
                CleanroomRing.Slot slot = new CleanroomRing.Slot();
                slot.envelope = prompt(i);
                cleanroom.onEvent(slot, i, i == 7);
            }
        });

        assertEquals(8, view.current().runs().size());
        stuck.countDown();
        input.shutdown(Duration.ZERO);
        output.shutdown(Duration.ZERO);
    }

    @Test
    void droppedCommandReachesTheKernelAsAFailure() throws Exception {
        CountDownLatch stuck = new CountDownLatch(1);
        OutputRing output = new OutputRing(2, (slot, sequence, end) -> stuck.await());
        output.start();
        InFlightCommands inFlight = new InFlightCommands();
        StateView view = new StateView();
        InputRing input = new InputRing(64);
        ResultPublisher results = new ResultPublisher(input, inFlight, Clock.systemUTC(), 0);
        Cleanroom cleanroom = LoopFixtures.cleanroom(kernel(), () -> false, output::tryPublish, LoopFixtures.NO_PROJECTION,
                (seq, index, fact) -> { }, view, results);
        long[] nextSeq = {0};
        input.connect((slot, sequence, end) -> {
            CleanroomRing.Slot next = new CleanroomRing.Slot();
            next.envelope = slot.envelope.withSeq(nextSeq[0]++);
            cleanroom.onEvent(next, sequence, end);
        });
        input.start();

        for (int i = 0; i < 8; i++) {
            SessionId session = new SessionId("s" + i);
            assertTrue(input.tryPublish(new EventEnvelope(EventEnvelope.UNASSIGNED, Instant.EPOCH, new Principal("u"), session, null,
                    new UserPrompt(new SubmissionId("sub" + i), new RequestId("req" + i), new OwnerRef.OfSession(session), "hi"))));
        }

        List<RunState> failed = new ArrayList<>();
        for (int i = 0; i < 500 && failed.isEmpty(); i++) {
            Thread.sleep(10);
            failed = view.current().runs().values().stream()
                    .filter(run -> run.phase() instanceof Ended ended && ended.reason() == EndReason.FAILED).toList();
        }
        assertFalse(failed.isEmpty(), "at least one run must fail because its command did not fit");
        assertEquals("output ring full", assertInstanceOf(Ended.class, failed.getFirst().phase()).detail());
        stuck.countDown();
        input.shutdown(Duration.ZERO);
        output.shutdown(Duration.ZERO);
    }

    @Test
    void fullInputRingReturnsImmediatelyAndTheDropIsReportedOnceThereIsRoom() throws Exception {
        CountDownLatch stuck = new CountDownLatch(1);
        List<EventEnvelope> received = java.util.Collections.synchronizedList(new ArrayList<>());
        InputRing input = LoopFixtures.inputRing(2, (slot, sequence, end) -> {
            stuck.await();
            received.add(slot.envelope);
        });
        input.start();
        ResultPublisher results = new ResultPublisher(input, new InFlightCommands(), Clock.systemUTC(), 0);

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            for (int i = 0; i < 8; i++) {
                tantalum.value.id.CommandId id = new tantalum.value.id.CommandId("c" + i);
                results.publish(new tantalum.value.command.AssembleContext(id, new tantalum.value.id.RunRef(new Principal("u"),
                        new SessionId("s"), new tantalum.value.id.RunId("r"))), new tantalum.value.event.TimerFired(id));
            }
        });
        assertTrue(results.pendingDrops() > 0);

        stuck.countDown();
        for (int i = 0; i < 500 && results.pendingDrops() > 0; i++) {
            results.flushDropped();
            Thread.sleep(5);
        }
        assertEquals(0, results.pendingDrops());
        input.shutdown(Duration.ZERO);
    }

    @Test
    void externalOverLimitGets429OverHttp() throws Exception {
        CountDownLatch hold = new CountDownLatch(1);
        ModelClient held = (prompt, chunks) -> {
            try {
                hold.await();
            } catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt();
            }
            return new ModelOutcome.Replied(List.of(), tantalum.value.model.StopReason.END_TURN, "m", null);
        };
        try (TestServer server = new TestServer(temp.resolve("data"), Files.createDirectory(temp.resolve("workspace")), held,
                "--tantalum.loop.max-active-runs=1")) {
            String first = "{\"requestId\":\"r1\",\"mode\":\"prompt\",\"text\":\"hi\"}";
            assertEquals(200, server.post("alice", "/api/sessions/s1/submissions", first).statusCode());
            String second = "{\"requestId\":\"r2\",\"mode\":\"prompt\",\"text\":\"hi\"}";
            assertEquals(429, server.post("bob", "/api/sessions/s2/submissions", second).statusCode());
            hold.countDown();
            // 活跃运行结束后，被拒绝的提交可以用同一个 requestId 重试，不会被去重表挡住。
            String retried = "";
            for (int i = 0; i < 500 && !retried.contains("submissionId"); i++) {
                Thread.sleep(10);
                retried = server.post("bob", "/api/sessions/s2/submissions", second).body();
            }
            assertTrue(retried.contains("\"duplicate\":false"), retried);
        }
    }
}
