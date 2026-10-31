package tantalum.shell.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.UserPrompt;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.SessionId;
import tantalum.value.id.SubmissionId;
import tantalum.value.state.OwnerRef;

class SubmissionLedgerTest {

    private static final Principal ALICE = new Principal("alice");
    private static final RequestId REQUEST = new RequestId("r");

    @Test
    void repeatedRequestGetsTheExistingSubmission() {
        SubmissionLedger ledger = new SubmissionLedger();
        SubmissionLedger.Claim first = ledger.claim(ALICE, REQUEST);
        SubmissionLedger.Claim second = ledger.claim(ALICE, REQUEST);
        assertTrue(first.isNew());
        assertEquals(first.submission(), second.submission());
        assertEquals(false, second.isNew());
    }

    @Test
    void sameRequestIdFromAnotherUserIsSeparate() {
        SubmissionLedger ledger = new SubmissionLedger();
        assertNotEquals(ledger.claim(ALICE, REQUEST).submission(), ledger.claim(new Principal("bob"), REQUEST).submission());
    }

    @Test
    void releasedClaimCanBeRetried() {
        SubmissionLedger ledger = new SubmissionLedger();
        ledger.release(ALICE, REQUEST);
        ledger.claim(ALICE, REQUEST);
        ledger.release(ALICE, REQUEST);
        assertTrue(ledger.claim(ALICE, REQUEST).isNew());
    }

    @Test
    void onlyOneOfManyConcurrentClaimsWins() throws Exception {
        SubmissionLedger ledger = new SubmissionLedger();
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
    void restartRebuildsFromTheJournal() {
        SessionId session = new SessionId("s");
        SubmissionId existing = new SubmissionId("existing");
        SubmissionLedger ledger = new SubmissionLedger();
        ledger.rebuild(List.of(new EventEnvelope(0, Instant.EPOCH, ALICE, session, null,
                new UserPrompt(existing, REQUEST, new OwnerRef.OfSession(session), "hi"))));
        SubmissionLedger.Claim claim = ledger.claim(ALICE, REQUEST);
        assertEquals(existing, claim.submission());
        assertEquals(false, claim.isNew());
    }
}
