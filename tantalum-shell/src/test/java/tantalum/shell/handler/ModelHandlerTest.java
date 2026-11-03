package tantalum.shell.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.shell.model.ChunkListener;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.ModelOutcome;
import tantalum.value.command.CallModel;
import tantalum.value.event.ModelFailed;
import tantalum.value.event.ModelInterrupted;
import tantalum.value.event.ModelReplied;
import tantalum.value.id.CommandId;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;
import tantalum.value.model.FailureReason;
import tantalum.value.model.Prompt;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolCallBlock;

class ModelHandlerTest {

    private static final RunRef RUN = new RunRef(new Principal("p"), new SessionId("s"), new RunId("r"));
    private static final CallModel COMMAND = new CallModel(new CommandId("c"), RUN, new Prompt("s", List.of(), List.of()));

    private static final Instant T0 = Instant.parse("2026-11-03T01:00:00Z");
    private static final Instant T1 = T0.plusMillis(250);
    private static final Instant T2 = T0.plusMillis(900);

    private final ManualClock clock = new ManualClock(T0);
    private final List<String> progress = new ArrayList<>();
    private ChunkListener listener(RunRef run) {
        return new ChunkListener() {
            @Override public void onText(String text) { progress.add(run.run().value() + ":text:" + text); }
            @Override public void onReasoning(String text) { progress.add(run.run().value() + ":reasoning:" + text); }
        };
    }

    private ModelHandler handler(ModelClient client, CallLimiter limiter, Duration wait) {
        return new ModelHandler(client, limiter, wait, this::listener, clock);
    }

    @Test
    void replyBecomesAnEventAndChunksOnlyGoToProgress() throws Exception {
        ModelClient client = (prompt, chunks) -> {
            chunks.onText("Hi");
            chunks.onReasoning("hm");
            return new ModelOutcome.Replied(List.of(new TextBlock("Hi")), StopReason.END_TURN, "m", null);
        };
        ModelReplied reply = assertInstanceOf(ModelReplied.class, handler(client, new CallLimiter(1), Duration.ofSeconds(1)).handle(COMMAND));
        assertEquals(COMMAND.id(), reply.command());
        assertEquals(List.of("r:text:Hi", "r:reasoning:hm"), progress);
    }

    @Test
    void failureBecomesAnEvent() throws Exception {
        ModelClient client = (prompt, chunks) -> new ModelOutcome.Failed(FailureReason.RATE_LIMITED, true, Duration.ofSeconds(3), "pa", "429");
        ModelFailed failed = assertInstanceOf(ModelFailed.class, handler(client, new CallLimiter(1), Duration.ofSeconds(1)).handle(COMMAND));
        assertEquals(Duration.ofSeconds(3), failed.retryAfter());
        assertEquals("pa", failed.partialText());
    }

    @Test
    void interruptedOutcomeBecomesModelInterrupted() throws Exception {
        ModelClient client = (prompt, chunks) -> new ModelOutcome.Interrupted("seen");
        assertEquals(new ModelInterrupted(COMMAND.id(), "seen", T0, null, null), handler(client, new CallLimiter(1), Duration.ofSeconds(1)).handle(COMMAND));
    }

    @Test
    void waitingForAModelSlotCountsAsATimeout() throws Exception {
        CallLimiter limiter = new CallLimiter(1);
        CallLimiter.Permit held = limiter.acquire(Duration.ofSeconds(1));
        ModelClient client = (prompt, chunks) -> { throw new AssertionError("must not be called"); };
        ModelFailed failed = assertInstanceOf(ModelFailed.class, handler(client, limiter, Duration.ofMillis(10)).handle(COMMAND));
        assertEquals(FailureReason.TIMEOUT, failed.reason());
        assertTrue(failed.retryable());
        held.close();
    }

    @Test
    void permitIsReturnedAfterTheCall() throws Exception {
        CallLimiter limiter = new CallLimiter(1);
        ModelClient client = (prompt, chunks) -> new ModelOutcome.Interrupted("");
        handler(client, limiter, Duration.ofSeconds(1)).handle(COMMAND);
        assertEquals(1, limiter.available());
    }

    @Test
    void interruptWhileWaitingEndsWithNoText() throws Exception {
        CallLimiter limiter = new CallLimiter(1);
        CallLimiter.Permit held = limiter.acquire(Duration.ofSeconds(1));
        ModelClient client = (prompt, chunks) -> { throw new AssertionError("must not be called"); };
        Thread.currentThread().interrupt();
        assertEquals(new ModelInterrupted(COMMAND.id(), "", null, null, null), handler(client, limiter, Duration.ofSeconds(5)).handle(COMMAND));
        Thread.interrupted();
        held.close();
    }

    @Test
    void unexpectedExceptionBecomesANonRetryableFailure() {
        ModelFailed failed = assertInstanceOf(ModelFailed.class,
                handler((p, c) -> null, new CallLimiter(1), Duration.ofSeconds(1)).failed(COMMAND, new IllegalStateException("boom")));
        assertFalse(failed.retryable());
        assertEquals(FailureReason.UNKNOWN, failed.reason());
    }

    @Test
    void progressFailureDoesNotBreakTheCall() throws Exception {
        ChunkListener broken = new ChunkListener() {
            @Override public void onText(String text) { throw new IllegalStateException("subscriber gone"); }
            @Override public void onReasoning(String text) { throw new IllegalStateException("subscriber gone"); }
        };
        ModelClient client = (prompt, chunks) -> {
            ChunkListener listener = chunks;
            listener.onText("Hi");
            return new ModelOutcome.Replied(List.of(), StopReason.END_TURN, "m", null);
        };
        assertInstanceOf(ModelReplied.class, new ModelHandler(client, new CallLimiter(1), Duration.ofSeconds(1), run -> broken, clock).handle(COMMAND));
    }

    // ---- 开始时刻和首 token：在处理器一处量，客户端只管协议 ----

    /** 首 token 是第一个内容分片到达的时刻：之后的分片不改它。 */
    @Test
    void firstTokenIsTheFirstTextChunk() throws Exception {
        ModelClient client = (prompt, chunks) -> {
            clock.now = T1;
            chunks.onText("Hel");
            clock.now = T2;
            chunks.onText("lo");
            chunks.onReasoning("late");
            return new ModelOutcome.Replied(List.of(new TextBlock("Hello")), StopReason.END_TURN, "m", null);
        };
        ModelReplied reply = assertInstanceOf(ModelReplied.class, handler(client, new CallLimiter(1), Duration.ofSeconds(1)).handle(COMMAND));
        assertEquals(T0, reply.startedAt());
        assertEquals(T1, reply.firstTokenAt());
        assertNull(reply.options(), "a client that does not report options leaves them unrecorded");
    }

    @Test
    void firstTokenMayBeReasoning() throws Exception {
        ModelClient client = (prompt, chunks) -> {
            clock.now = T1;
            chunks.onReasoning("hm");
            clock.now = T2;
            chunks.onText("Hi");
            return new ModelOutcome.Replied(List.of(new TextBlock("Hi")), StopReason.END_TURN, "m", null);
        };
        ModelReplied reply = assertInstanceOf(ModelReplied.class, handler(client, new CallLimiter(1), Duration.ofSeconds(1)).handle(COMMAND));
        assertEquals(T1, reply.firstTokenAt());
        assertEquals(List.of("r:reasoning:hm", "r:text:Hi"), progress);
    }

    /** 只回工具调用的回复也有首 token；工具输入不推给进度监听者。 */
    @Test
    void toolInputAloneCountsAsTheFirstToken() throws Exception {
        ModelClient client = (prompt, chunks) -> {
            clock.now = T1;
            chunks.onToolInput("");
            clock.now = T2;
            chunks.onToolInput("{\"path\":");
            return new ModelOutcome.Replied(List.of(new ToolCallBlock("t1", "read_file", "{}", null)), StopReason.TOOL_USE, "m", null);
        };
        ModelReplied reply = assertInstanceOf(ModelReplied.class, handler(client, new CallLimiter(1), Duration.ofSeconds(1)).handle(COMMAND));
        assertEquals(T0, reply.startedAt());
        assertEquals(T1, reply.firstTokenAt());
        assertEquals(List.of(), progress);
    }

    /** 失败和中断同样带计时；一个分片都没有时首 token 为空。 */
    @Test
    void failuresAndInterruptsCarryTheTiming() throws Exception {
        ModelClient failing = (prompt, chunks) -> {
            clock.now = T1;
            chunks.onText("pa");
            return new ModelOutcome.Failed(FailureReason.CONNECTION_ERROR, true, null, "pa", "reset");
        };
        ModelFailed failed = assertInstanceOf(ModelFailed.class, handler(failing, new CallLimiter(1), Duration.ofSeconds(1)).handle(COMMAND));
        assertEquals(T0, failed.startedAt());
        assertEquals(T1, failed.firstTokenAt());

        clock.now = T2;
        ModelClient silent = (prompt, chunks) -> new ModelOutcome.Failed(FailureReason.TIMEOUT, true, null, "", "no first chunk");
        ModelFailed timedOut = assertInstanceOf(ModelFailed.class, handler(silent, new CallLimiter(1), Duration.ofSeconds(1)).handle(COMMAND));
        assertEquals(T2, timedOut.startedAt());
        assertNull(timedOut.firstTokenAt());
    }

    /** 等名额是排队：开始时刻在拿到名额之后取，而不是命令到达处理器时。 */
    @Test
    void startedAtIsTakenAfterTheModelSlot() throws Exception {
        CallLimiter limiter = new CallLimiter(1);
        CallLimiter.Permit held = limiter.acquire(Duration.ofSeconds(1));
        ModelHandler handler = handler((prompt, chunks) -> new ModelOutcome.Replied(List.of(), StopReason.END_TURN, "m", null),
                limiter, Duration.ofSeconds(10));
        CompletableFuture<ModelReplied> result = new CompletableFuture<>();
        Thread waiting = new Thread(() -> result.complete((ModelReplied) handler.handle(COMMAND)));
        waiting.start();
        for (int i = 0; i < 1000 && waiting.getState() != Thread.State.TIMED_WAITING; i++) {
            Thread.sleep(2);
        }
        assertEquals(Thread.State.TIMED_WAITING, waiting.getState(), "handler should be waiting for the slot");

        clock.now = T1;
        held.close();

        assertEquals(T1, result.get(5, TimeUnit.SECONDS).startedAt());
    }

    /** 没拿到名额就没有发出请求：不记开始时刻。 */
    @Test
    void noSlotMeansNoStart() throws Exception {
        CallLimiter limiter = new CallLimiter(1);
        CallLimiter.Permit held = limiter.acquire(Duration.ofSeconds(1));
        ModelFailed failed = assertInstanceOf(ModelFailed.class,
                handler((prompt, chunks) -> { throw new AssertionError("must not be called"); }, limiter, Duration.ofMillis(10)).handle(COMMAND));
        assertNull(failed.startedAt());
        assertNull(failed.firstTokenAt());
        assertNull(failed.options());
        held.close();
    }
}
