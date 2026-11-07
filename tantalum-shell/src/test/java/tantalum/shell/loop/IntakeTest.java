package tantalum.shell.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.SessionId;

/** review-fixes 需求 1：提交的回执不说谎——被拒的提交没有在途回执，索引查不了回 503。 */
class IntakeTest {

    private static final Principal ALICE = new Principal("alice");
    private static final SessionId SESSION = new SessionId("s-1");

    private final Receipts receipts = new Receipts();

    /** {@code maxUserInFlight} 为 1：一个名额漏掉，下一次提交就会被拒，所以能看出名额有没有退回。 */
    private Intake intake(SubmissionLedger.History history, int ringSize) {
        InputRing input = new InputRing(ringSize);
        ExternalGate gate = new ExternalGate(new ExternalGate.Limits(4, 1, 4), () -> 0, () -> false);
        SubmissionLedger ledger = new SubmissionLedger(history, SubmissionLedger.DEFAULT_CAPACITY);
        return new Intake(input, gate, ledger, new StateView(), new Residency(), (principal, session) -> 200,
                (principal, session) -> Optional.empty(), Clock.systemUTC(), receipts);
    }

    /** 环没有消费者：第一条进去，第二条 429。被拒的那条没有在途回执，再试一次是新的认领而不是“重复”。 */
    @Test
    void aRejectedSubmissionLeavesNoReceiptAndCanBeRetried() {
        Intake intake = intake((principal, request) -> Optional.empty(), 1);
        Intake.Submission first = intake.submit(ALICE, SESSION, new RequestId("r-1"), "prompt", "hi");
        assertEquals(200, first.status());
        assertFalse(first.saved().isDone(), "waits for the step to be durable");
        Intake.Submission second = intake.submit(ALICE, SESSION, new RequestId("r-2"), "prompt", "again");
        assertEquals(429, second.status());
        assertNull(second.saved());
        assertEquals(1, receipts.size(), "only the accepted submission has a receipt in flight");
        Intake.Submission retried = intake.submit(ALICE, SESSION, new RequestId("r-2"), "prompt", "again");
        assertEquals(429, retried.status(), "a fresh attempt that reaches the full ring again, not a 503 from a leaked permit");
        assertFalse(retried.duplicate(), "the rejected claim was released, so the retry is a fresh attempt");
    }

    /** 需求 1.4：去重的索引查不了时回 503，不占名额、不留回执，也不当作新提交；索引恢复后同一个请求照常接受。 */
    @Test
    void anUnavailableIndexRefusesTheSubmissionInsteadOfTreatingItAsNew() {
        boolean[] locked = {true};
        Intake intake = intake((principal, request) -> {
            if (locked[0]) {
                throw new UncheckedIOException(new IOException("index locked"));
            }
            return Optional.empty();
        }, 4);
        Intake.Submission refused = intake.submit(ALICE, SESSION, new RequestId("r-1"), "prompt", "hi");
        assertEquals(503, refused.status());
        assertEquals(0, receipts.size());
        locked[0] = false;
        Intake.Submission accepted = intake.submit(ALICE, SESSION, new RequestId("r-1"), "prompt", "hi");
        assertEquals(200, accepted.status());
        assertFalse(accepted.duplicate(), "the refused attempt was not recorded as a submission");
    }

    /** 重复提交拿到的是原提交那张还在途的回执。 */
    @Test
    void aDuplicateSharesTheOriginalReceipt() {
        Intake intake = intake((principal, request) -> Optional.empty(), 4);
        Intake.Submission first = intake.submit(ALICE, SESSION, new RequestId("r-1"), "prompt", "hi");
        Intake.Submission again = intake.submit(ALICE, SESSION, new RequestId("r-1"), "prompt", "hi");
        assertTrue(again.duplicate());
        assertEquals(first.submission(), again.submission());
        assertTrue(first.saved() == again.saved() || !again.saved().isDone(), "the duplicate waits like the original");
        assertEquals(1, receipts.size());
    }
}
