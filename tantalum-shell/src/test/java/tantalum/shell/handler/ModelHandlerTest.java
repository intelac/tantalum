package tantalum.shell.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
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

class ModelHandlerTest {

    private static final RunRef RUN = new RunRef(new Principal("p"), new SessionId("s"), new RunId("r"));
    private static final CallModel COMMAND = new CallModel(new CommandId("c"), RUN, new Prompt("s", List.of(), List.of()));

    private final List<String> progress = new ArrayList<>();
    private final ModelHandler.Progress listener = (run, kind, text) -> progress.add(run.run().value() + ":" + kind + ":" + text);

    private ModelHandler handler(ModelClient client, CallLimiter limiter, Duration wait) {
        return new ModelHandler(client, limiter, wait, listener);
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
        assertEquals(new ModelInterrupted(COMMAND.id(), "seen"), handler(client, new CallLimiter(1), Duration.ofSeconds(1)).handle(COMMAND));
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
        assertEquals(new ModelInterrupted(COMMAND.id(), ""), handler(client, limiter, Duration.ofSeconds(5)).handle(COMMAND));
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
        ModelHandler.Progress broken = (run, kind, text) -> { throw new IllegalStateException("subscriber gone"); };
        ModelClient client = (prompt, chunks) -> {
            ChunkListener listener = chunks;
            listener.onText("Hi");
            return new ModelOutcome.Replied(List.of(), StopReason.END_TURN, "m", null);
        };
        assertInstanceOf(ModelReplied.class, new ModelHandler(client, new CallLimiter(1), Duration.ofSeconds(1), broken).handle(COMMAND));
    }
}
