package tantalum.shell.loop;

import static org.junit.jupiter.api.Assertions.assertThrows;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.SubmissionId;

class SubmissionLedgerTest {

    private static final Principal ALICE = new Principal("alice");
    private static final RequestId REQUEST = new RequestId("r");

    @Test
    void repeatedRequestGetsTheExistingSubmission() {
        SubmissionLedger ledger = new SubmissionLedger((principal, request) -> Optional.empty(), SubmissionLedger.DEFAULT_CAPACITY);
        SubmissionLedger.Claim first = ledger.claim(ALICE, REQUEST);
        SubmissionLedger.Claim second = ledger.claim(ALICE, REQUEST);
        assertTrue(first.isNew());
        assertEquals(first.submission(), second.submission());
        assertEquals(false, second.isNew());
    }

    @Test
    void sameRequestIdFromAnotherUserIsSeparate() {
        SubmissionLedger ledger = new SubmissionLedger((principal, request) -> Optional.empty(), SubmissionLedger.DEFAULT_CAPACITY);
        assertNotEquals(ledger.claim(ALICE, REQUEST).submission(), ledger.claim(new Principal("bob"), REQUEST).submission());
    }

    @Test
    void releasedClaimCanBeRetried() {
        SubmissionLedger ledger = new SubmissionLedger((principal, request) -> Optional.empty(), SubmissionLedger.DEFAULT_CAPACITY);
        ledger.release(ALICE, REQUEST);
        ledger.claim(ALICE, REQUEST);
        ledger.release(ALICE, REQUEST);
        assertTrue(ledger.claim(ALICE, REQUEST).isNew());
    }

    @Test
    void onlyOneOfManyConcurrentClaimsWins() throws Exception {
        SubmissionLedger ledger = new SubmissionLedger((principal, request) -> Optional.empty(), SubmissionLedger.DEFAULT_CAPACITY);
        Set<SubmissionId> submissions = ConcurrentHashMap.newKeySet();
        List<Boolean> wins = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            threads.add(Thread.ofVirtual().start(() -> {
                SubmissionLedger.Claim claim = ledger.claim(ALICE, REQUEST);
                submissions.add(claim.submission());
                wins.add(claim.isNew());
            }));
        }
        for (Thread thread : threads) {
            thread.join();
        }
        assertEquals(1, submissions.size());
        assertEquals(1, wins.stream().filter(win -> win).count());
    }

    @Test
    void historyAnswersWhatMemoryForgot() {
        SubmissionId old = new SubmissionId("old");
        List<RequestId> asked = new ArrayList<>();
        SubmissionLedger ledger = new SubmissionLedger((principal, request) -> {
            asked.add(request);
            return request.equals(REQUEST) ? java.util.Optional.of(old) : java.util.Optional.empty();
        }, 2);
        assertEquals(new SubmissionLedger.Claim(old, false), ledger.claim(ALICE, REQUEST));
        ledger.claim(ALICE, new RequestId("a"));
        ledger.claim(ALICE, new RequestId("b"));
        assertEquals(new SubmissionLedger.Claim(old, false), ledger.claim(ALICE, REQUEST), "evicted from memory, still a duplicate");
        assertEquals(2, asked.stream().filter(REQUEST::equals).count());
    }

    @Test
    void memoryHoldsNoMoreThanItsCapacity() throws Exception {
        List<RequestId> asked = new ArrayList<>();
        SubmissionLedger ledger = new SubmissionLedger((principal, request) -> {
            asked.add(request);
            return java.util.Optional.empty();
        }, 2);
        ledger.claim(ALICE, new RequestId("a"));
        ledger.claim(ALICE, new RequestId("b"));
        ledger.claim(ALICE, new RequestId("a"));
        assertEquals(2, asked.size(), "recent requests do not reach the index");
        ledger.claim(ALICE, new RequestId("c"));
        ledger.claim(ALICE, new RequestId("b"));
        assertEquals(4, asked.size(), "b was the least recently used and fell out");
    }

    /** review-fixes 需求 1.1：{@code onNew} 在认领对别人可见之前运行；它抛异常则没有认领。 */
    @Test
    void onNewRunsBeforeTheClaimIsVisibleAndAThrowingOnNewLeavesNoClaim() {
        SubmissionLedger ledger = new SubmissionLedger((principal, request) -> Optional.empty(), SubmissionLedger.DEFAULT_CAPACITY);
        List<SubmissionId> seen = new ArrayList<>();
        SubmissionLedger.Claim first = ledger.claim(ALICE, REQUEST, seen::add);
        assertEquals(List.of(first.submission()), seen);
        assertThrows(IllegalStateException.class, () -> ledger.claim(ALICE, new RequestId("r2"), ignored -> {
            throw new IllegalStateException("no receipt");
        }));
        assertTrue(ledger.claim(ALICE, new RequestId("r2")).isNew(), "the failed claim was never recorded");
    }

    /** 需求 1.4：索引查不了时抛出，不当作新提交。 */
    @Test
    void anIndexFailurePropagates() {
        SubmissionLedger ledger = new SubmissionLedger((principal, request) -> {
            throw new java.io.UncheckedIOException(new java.io.IOException("locked"));
        }, SubmissionLedger.DEFAULT_CAPACITY);
        assertThrows(java.io.UncheckedIOException.class, () -> ledger.claim(ALICE, REQUEST));
    }
}
