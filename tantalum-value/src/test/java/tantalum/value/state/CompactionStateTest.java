package tantalum.value.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.value.hook.PromptPatch;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;
import tantalum.value.model.Message;

class CompactionStateTest {

    private static final SessionId SESSION = new SessionId("s");
    private static final Principal USER = new Principal("u");
    private static final CompactionSettings SETTINGS = new CompactionSettings(200_000, 64_000, 20_000, 32_768, 4_096, 8_192);

    @Test
    void settingsDeriveThresholdAndKeepBudget() {
        assertEquals(136_000, SETTINGS.threshold());
        assertEquals(20_000, SETTINGS.keepBudget());
        CompactionSettings small = new CompactionSettings(32_000, 16_000, 20_000, 32_768, 4_096, 8_192);
        assertEquals(4_000, small.keepBudget(), "K is capped at a quarter of the threshold");
    }

    @Test
    void settingsRejectNonPositiveAndReserveNotBelowWindow() {
        assertThrows(IllegalArgumentException.class, () -> new CompactionSettings(0, 1, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new CompactionSettings(10, 10, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new CompactionSettings(10, 1, 0, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new CompactionSettings(10, 1, 1, 1, 1, -5));
    }

    @Test
    void viewIsTheHistoryUntilCompacted() {
        SessionState session = SessionState.start(SESSION, USER).append(Message.user("a")).append(Message.user("b"));
        assertSame(session.messages(), session.view());
        assertNull(session.compaction());
        assertNull(session.anchor());
    }

    @Test
    void viewIsReplacementPlusMessagesAppendedAfterwards() {
        SessionState session = SessionState.start(SESSION, USER).append(Message.user("a")).append(Message.user("b"))
                .withAnchor(new UsageAnchor(100, 2));
        Message summary = Message.user("[Summary] a and b");
        SessionState compacted = session.compacted(new Compaction(List.of(summary), 2));
        assertEquals(List.of(summary), compacted.view());
        assertNull(compacted.anchor(), "the old measurement is void after compaction");
        assertEquals(2, compacted.messages().size(), "history is untouched");

        SessionState later = compacted.append(Message.user("c"));
        assertEquals(List.of(summary, Message.user("c")), later.view());
        assertEquals(3, later.messages().size());
    }

    @Test
    void tailBeyondHistoryIsRejected() {
        SessionState session = SessionState.start(SESSION, USER).append(Message.user("a"));
        assertThrows(IllegalArgumentException.class, () -> session.compacted(new Compaction(List.of(), 2)));
        assertThrows(IllegalArgumentException.class, () -> new Compaction(List.of(), -1));
    }

    @Test
    void sessionUpdatesKeepCompactionAndAnchor() {
        Compaction compaction = new Compaction(List.of(Message.user("s")), 0);
        SessionState session = SessionState.start(SESSION, USER).compacted(compaction).withAnchor(new UsageAnchor(5, 0));
        SessionState changed = session.append(Message.user("x")).startRun(new RunId("r")).withFollowUps(List.of(Message.user("f")))
                .withContext(new SessionContext("sys", List.of(), SETTINGS, List.of()));
        assertEquals(compaction, changed.compaction());
        assertEquals(new UsageAnchor(5, 0), changed.anchor());
        assertEquals(SETTINGS, changed.context().compaction());
        assertNull(new SessionContext("sys", List.of(), null, List.of()).compaction());
        assertEquals(SETTINGS, changed.context().withSections(List.of()).compaction());
    }

    @Test
    void runCarriesCompactionFlags() {
        RunRef ref = new RunRef(USER, SESSION, new RunId("r"));
        Plan plan = new Plan(3, 2, 1, 900, null);
        Compacting compacting = new Compacting(new Compacting.Overflow("prompt is too long"), plan, PromptPatch.NONE);
        RunState run = RunState.start(ref, new OwnerRef.OfSession(SESSION))
                .withCompacting(compacting).withAutoCompactionFailed(true).withOverflowRecovered(true);
        assertEquals(compacting, run.compacting());
        assertTrue(run.autoCompactionFailed());
        assertTrue(run.overflowRecovered());
        assertEquals(compacting, run.withRetries(1).withPhase(new Deciding()).compacting());
        assertNull(run.withCompacting(null).compacting());
        assertThrows(IllegalArgumentException.class, () -> new Plan(0, 0, 0, 0, null));
        assertThrows(NullPointerException.class, () -> new Compacting(null, plan, PromptPatch.NONE));
        assertEquals(Compacting.Trigger.OVERFLOW, compacting.trigger());
    }
}
