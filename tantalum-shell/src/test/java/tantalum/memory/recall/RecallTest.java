package tantalum.memory.recall;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.memory.ledger.Ledger;
import tantalum.memory.ledger.MemoryItem;

/** memory-final-plan 第 9 节：关键词（中日韩两字一组）、范围、取代、待确认、时间。 */
class RecallTest {

    private static final Instant NOW = Instant.parse("2026-11-16T03:00:00Z");

    @TempDir
    Path dir;

    private Ledger ledger;

    private MemoryItem page(String name, String description) {
        MemoryItem page = MemoryItem.create(ledger.newId(), MemoryItem.Kind.PAGE, MemoryItem.Source.USER_SAID, MemoryItem.Actor.USER, "", NOW)
                .withPage(name).withAttrs(Map.of("type", "decision", "description", description));
        ledger.put(page, 0);
        return page;
    }

    private MemoryItem claim(String page, String text, int daysAgo) {
        Instant at = NOW.minusSeconds(86_400L * daysAgo);
        MemoryItem claim = new MemoryItem(ledger.newId(), 1, MemoryItem.State.CONFIRMED, MemoryItem.Kind.CLAIM, page, MemoryItem.PERSONAL,
                MemoryItem.Source.USER_SAID, null, null, MemoryItem.Actor.USER, at, null, null, MemoryItem.Confidence.OBSERVED, List.of(), null,
                false, text, Map.of(), null, List.of(), null, null, at, at);
        ledger.put(claim, 0);
        return ledger.get(claim.id()).orElseThrow();
    }

    private List<String> found(String question, String dirOfSession) {
        return new Recall(ledger).search(question, "", dirOfSession, ZoneOffset.UTC).stream().map(hit -> hit.item().text()).toList();
    }

    @Test
    void termsAreWordsAndCjkPairsWithoutFunctionWords() {
        assertEquals(Set.of("数据", "据库", "库用"), Terms.of("我们数据库用什么来着？"));
        assertEquals(Set.of("production", "cluster"), Terms.of("Which cluster does production run on?"));
        assertEquals(Set.of("mysql"), Terms.of("我们定的是 MySQL 对吧？"));
    }

    @Test
    void timeWordsBecomeAWindowOfDays() {
        LocalDate today = LocalDate.of(2026, 11, 16);
        assertEquals(new TimeWindow(LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 14)), TimeWindow.in("三天前我们定了什么？", today).orElseThrow());
        assertEquals(new TimeWindow(LocalDate.of(2026, 11, 14), LocalDate.of(2026, 11, 16)), TimeWindow.in("what did we decide yesterday", today).orElseThrow());
        assertTrue(TimeWindow.in("我们数据库用什么", today).isEmpty());
    }

    @Test
    void findsByKeywordsRespectsScopeSupersessionAndTime() {
        ledger = Ledger.open(dir, Clock.fixed(NOW, ZoneOffset.UTC));
        page("decision-database", "数据库选型");
        page("preference-language", "回答的语言和风格");
        page("project-amber", "分支约定");
        page("decision-release", "发布安排");
        claim("decision-database", "数据库定了 PostgreSQL 16，不用 MySQL。", 20);
        MemoryItem old = claim("preference-language", "回答请用英文。", 12);
        MemoryItem newer = claim("preference-language", "以后回答用简体中文。", 1);
        ledger.put(newer.withAttrs(Map.of("supersedes", old.id())), newer.version());
        MemoryItem branch = claim("project-amber", "发布分支叫 release/amber-cut。", 6);
        ledger.put(branch.withScope("project:/work/alpha"), branch.version());
        claim("decision-release", "发布日期改到 12 月 1 日。", 3);

        assertEquals(List.of("数据库定了 PostgreSQL 16，不用 MySQL。"), found("我们数据库用什么来着？", null));
        assertEquals(List.of("数据库定了 PostgreSQL 16，不用 MySQL。"), found("我们定的是 MySQL 对吧？", null), "one keyword is enough when there is only one");
        assertEquals(List.of("以后回答用简体中文。"), found("我对回答的语言有什么要求？", null), "the superseded preference is not given");
        assertTrue(found("发布分支叫什么？", "/work/beta").isEmpty(), "another project's memory stays there");
        assertEquals(List.of("发布分支叫 release/amber-cut。"), found("发布分支叫什么？", "/work/alpha/sub"));
        assertTrue(found("三天前我们定了什么？", null).contains("发布日期改到 12 月 1 日。"));
        assertTrue(found("我的护照号是多少？", null).isEmpty(), "nothing is forced in when nothing matches");
    }
}
