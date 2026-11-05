package tantalum.shell.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;
import tantalum.value.event.Cancel;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.UserPrompt;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.SessionId;
import tantalum.value.id.SubmissionId;
import tantalum.value.state.OwnerRef;

/** one-line-journal 需求 2.1、2.4：提交的回执在那一步落盘后完成，没写进去时失败。 */
class ReceiptsTest {

    private static final SessionId SESSION = new SessionId("s-1");
    private static final SubmissionId SUBMISSION = new SubmissionId("sub-1");

    private final Receipts receipts = new Receipts();

    private static EventEnvelope submitted() {
        return new EventEnvelope(0, Instant.EPOCH, new Principal("alice"), SESSION, null,
                new UserPrompt(SUBMISSION, new RequestId("r-1"), new OwnerRef.OfSession(SESSION), "hi"));
    }

    @Test
    void completesWhenTheStepIsDurableAndStaysCompleted() {
        CompletableFuture<Void> saved = receipts.expect(SUBMISSION);
        assertTrue(!saved.isDone());

        receipts.durable(submitted());
        receipts.processed(submitted());

        assertTrue(saved.isDone() && !saved.isCompletedExceptionally());
        assertEquals(0, receipts.size());
    }

    @Test
    void failsWhenTheEventLeavesThePipelineWithoutBeingSaved() {
        CompletableFuture<Void> saved = receipts.expect(SUBMISSION);

        receipts.processed(submitted());

        ExecutionException failure = assertThrows(ExecutionException.class, saved::get);
        assertInstanceOf(Receipts.NotSaved.class, failure.getCause());
        assertEquals(0, receipts.size());
    }

    @Test
    void aDuplicateWaitsForTheSameReceiptWhileTheOriginalIsInFlight() {
        CompletableFuture<Void> original = receipts.expect(SUBMISSION);
        assertTrue(receipts.of(SUBMISSION) == original);

        receipts.durable(submitted());

        assertTrue(receipts.of(SUBMISSION).isDone(), "an original no longer in flight was saved earlier");
    }

    @Test
    void otherEventsDoNotTouchReceipts() {
        CompletableFuture<Void> saved = receipts.expect(SUBMISSION);
        EventEnvelope cancel = new EventEnvelope(1, Instant.EPOCH, new Principal("alice"), SESSION, null, new Cancel(new RequestId("c")));

        receipts.durable(cancel);
        receipts.processed(cancel);

        assertTrue(!saved.isDone());
    }
}
