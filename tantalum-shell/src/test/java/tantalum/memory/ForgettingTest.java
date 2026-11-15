package tantalum.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.memory.ledger.Ledger;
import tantalum.memory.ledger.MemoryItem;
import tantalum.memory.recall.Recall;

/**
 * memory-final-plan 第 9 节（图书管理员）、第 10 节①（不再使用）：忘掉一条时连带衍生的、卡里复述的、工作记忆里的；忘掉以前的副本找不回，
 * 忘掉以后又说的照样记；整理只合并意思一样的说法，找得到的不能变少。
 */
class ForgettingTest {

    @TempDir
    Path dir;

    private Ledger ledger;
    private MemoryStore store;

    private void open(Instant now) {
        ledger = Ledger.open(dir, Clock.fixed(now, ZoneOffset.UTC));
        store = new MemoryStore(ledger, MemoryLimits.defaults());
    }

    private MemoryItem claim(String page, String text) {
        return store.addClaim(page, "person", "about " + page, MemoryItem.create(ledger.newId(), MemoryItem.Kind.CLAIM,
                MemoryItem.Source.USER_SAID, MemoryItem.Actor.USER, text, ledger.clock().instant())).orElseThrow();
    }

    private List<String> recalled(String question) {
        return new Recall(ledger).search(question, "", null, ZoneOffset.UTC).stream().map(hit -> hit.item().text()).toList();
    }

    @Test
    void forgettingTakesDerivedItemsRestatedLinesAndWorkingMemoryWithIt() {
        open(Instant.parse("2026-11-16T01:00:00Z"));
        MemoryItem plate = claim("person-alice", "我的车牌号是 京A-7Q2KD。");
        MemoryItem derived = MemoryItem.create(ledger.newId(), MemoryItem.Kind.CLAIM, MemoryItem.Source.ASSISTANT_INFERRED,
                MemoryItem.Actor.AGENT, "停车证要用车牌 京A-7Q2KD 办。", ledger.clock().instant()).withDerivedFrom(List.of(plate.id()));
        store.addClaim("errand-parking", "project", "办停车证", derived);
        new DailyNotes(ledger).append("2026-11-15", "s-1", "车", "s-1_1", "10:00", "- 用户的车牌号是 京A-7Q2KD\n- 用户周三去物业");
        new WorkingMemory(ledger).add(MemoryItem.Kind.TASK, "用车牌 京A-7Q2KD 办停车证", Map.of(), MemoryItem.Source.USER_SAID,
                MemoryItem.Actor.USER, null, null, false, null);
        assertTrue(recalled("我的车牌号是多少？").stream().anyMatch(text -> text.contains("7Q2KD")));

        assertTrue(store.delete("person-alice", MemoryItem.Actor.USER));
        assertTrue(ledger.items(item -> item.text().contains("7Q2KD")).isEmpty(), "nothing alive mentions the plate");
        assertEquals("- 用户周三去物业", new DailyNotes(ledger).read("2026-11-15", "s-1").orElseThrow().sections().getFirst().body(),
                "the other line of the card stays");
        assertTrue(recalled("我那辆车挂的什么牌照？").isEmpty());
    }

    @Test
    void whatYouSayAgainAfterForgettingIsNewAndKept() {
        open(Instant.parse("2026-11-16T01:00:00Z"));
        claim("person-alice", "我的车牌号是 京A-7Q2KD。");
        assertEquals(1, Forgetting.forget(ledger, "车牌号 京A-7Q2KD", MemoryItem.Actor.USER));
        open(Instant.parse("2026-11-16T02:00:00Z"));
        claim("person-alice", "我的车牌号是 京A-7Q2KD，换了车又用回原来的号。");
        assertFalse(recalled("我的车牌号是多少？").isEmpty(), "a statement made after forgetting is new information");
    }

    @Test
    void theLibrarianMergesTheSameStatementOnceADayAndKeepsWhatCanBeFound() {
        open(Instant.parse("2026-11-16T01:00:00Z"));
        MemoryItem first = claim("preferences", "回答用简体中文。");
        store.ledger().put(MemoryItem.create(ledger.newId(), MemoryItem.Kind.CLAIM, MemoryItem.Source.USER_SAID, MemoryItem.Actor.USER,
                "回答用简体中文", ledger.clock().instant()).withPage("preferences"), 0);
        Librarian librarian = new Librarian(ledger, ZoneOffset.UTC);
        Librarian.Result result = librarian.daily();
        assertNotNull(result);
        assertEquals(1, result.merged());
        assertFalse(result.rolledBack());
        assertEquals(List.of(first.id()), ledger.items(item -> item.kind() == MemoryItem.Kind.CLAIM && item.live()).stream()
                .map(MemoryItem::id).toList());
        assertNotNull(ledger.get(first.id()).orElseThrow().lastConfirmed(), "corroborated");
        assertNull(librarian.daily(), "once a day");
    }
}
