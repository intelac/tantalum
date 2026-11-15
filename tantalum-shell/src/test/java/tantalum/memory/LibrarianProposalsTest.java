package tantalum.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.memory.ledger.Ledger;
import tantalum.memory.ledger.MemoryItem;

/** memory-final-plan 第 9 节：图书管理员的提议只进“待确认”；原话要在最近的笔记里；确认后改正取代旧说法、关掉那件事。 */
class LibrarianProposalsTest {

    private static final Instant NOW = Instant.parse("2026-11-16T03:00:00Z");

    @TempDir
    Path dir;

    @Test
    void proposalsWaitForTheUserAndTakeEffectWhenConfirmed() {
        Ledger ledger = Ledger.open(dir, Clock.fixed(NOW, ZoneOffset.UTC));
        MemoryStore store = new MemoryStore(ledger, MemoryLimits.defaults());
        WorkingMemory working = new WorkingMemory(ledger);
        MemoryItem old = store.addClaim("decisions", "decision", "Decisions made and why", MemoryItem.create(ledger.newId(),
                MemoryItem.Kind.CLAIM, MemoryItem.Source.USER_SAID, MemoryItem.Actor.USER, "发布日期定在 11 月 20 日。", NOW)).orElseThrow();
        new DailyNotes(ledger).append("2026-11-15", "s-1", "发布", "s-1_1", "10:00", "- 发布改到 12 月 1 号\n- 数据库用 PostgreSQL");
        Ledger earlier = Ledger.open(dir, Clock.fixed(NOW.minusSeconds(86_400 * 10), ZoneOffset.UTC));
        MemoryItem task = new WorkingMemory(earlier).add(MemoryItem.Kind.TASK, "发版前写说明", Map.of(), MemoryItem.Source.USER_SAID,
                MemoryItem.Actor.USER, null, null, false, null).orElseThrow();
        ledger = Ledger.open(dir, Clock.fixed(NOW, ZoneOffset.UTC));
        store = new MemoryStore(ledger, MemoryLimits.defaults());
        working = new WorkingMemory(ledger);
        String reply = "- [fix " + old.id() + "] 发布日期改到 12 月 1 日 «发布改到 12 月 1 号»\n"
                + "- [add preferences] 喜欢简洁 «编出来的原话»\n"
                + "- [close " + task.id() + "] 说明已经写了\n"
                + "- [add decisions] 数据库用 PostgreSQL «数据库用 PostgreSQL»";
        LibrarianProposals.Result result = new LibrarianProposals(ledger, store, working, ZoneOffset.UTC).propose((system, text) -> {
            assertTrue(text.contains("发布改到 12 月 1 号") && text.contains(old.id()) && text.contains(task.id()), text);
            return Optional.of(reply);
        });
        assertEquals(new LibrarianProposals.Result(1, 1, 1), result, "the made-up quote is not taken");
        assertTrue(ledger.get(old.id()).orElseThrow().live(), "nothing changes before the user confirms");
        assertEquals(1, working.open().size());

        PendingItems pending = new PendingItems(ledger);
        List<MemoryItem> proposals = pending.list();
        assertEquals(3, proposals.size());
        for (MemoryItem proposal : proposals) {
            assertEquals(PendingItems.Outcome.DONE, pending.confirm(proposal.id()));
        }
        assertFalse(ledger.get(old.id()).orElseThrow().live(), "the correction replaced the old statement");
        assertTrue(store.read("decisions").orElseThrow().content().contains("12 月 1 日"));
        assertTrue(working.open().isEmpty(), "the stale task is closed");
    }
}
