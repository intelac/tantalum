package tantalum.memory.recall;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /** 问“昨天做了什么”：给的是那几天的经过，当天卡优先、按时间从早到晚；同一天的先后看得出来（写到分钟）。 */
    @Test
    void aQuestionAboutADayGetsThatDayInOrder() {
        ledger = Ledger.open(dir, Clock.fixed(NOW, ZoneOffset.UTC));
        page("about-me", "关于用户");
        for (int i = 0; i < 14; i++) {
            claim("about-me", "很久以前的第 " + i + " 条事实", 30);
        }
        claim("about-me", "库存系统的代码在 inventory 目录", 1);
        tantalum.memory.DailyNotes daily = new tantalum.memory.DailyNotes(ledger);
        daily.append("2026-11-15", "s-1", "库存", "s-1_1", "10:00", "> 把海外那个区的运费费率调一下");
        daily.append("2026-11-15", "s-1", "库存", "s-1_2", "10:05", "> 算了，刚才调费率那一下撤回去吧");
        List<Recall.Hit> hits = new Recall(ledger).search("昨天库存那边我们都动了什么？", "", null, ZoneOffset.UTC);
        List<String> texts = hits.stream().map(hit -> hit.item().text()).toList();
        assertTrue(texts.containsAll(List.of("> 把海外那个区的运费费率调一下", "> 算了，刚才调费率那一下撤回去吧")), texts.toString());
        assertTrue(texts.stream().noneMatch(text -> text.startsWith("很久以前")), "only that period");
        assertTrue(texts.indexOf("> 把海外那个区的运费费率调一下") < texts.indexOf("> 算了，刚才调费率那一下撤回去吧"), "earlier first");
        String shown = RecallText.of(new Recall.Found(hits, 0, hits.size()), ZoneOffset.UTC);
        assertTrue(shown.contains("2026-11-16 03:00 · from that conversation: what follows > is what the user said"), shown);
        assertFalse(shown.contains("not shown"), "nothing was left out, so nothing is said about more");
    }

    // LongMemEval 11-19：问“厨房里换过修过几样”，五件事都记着，但一次只给 6 条，每件事又占两条（说法和它出自的当天卡），
    // 模型只看到三件就数成三。一张卡的小结逐字就是已给出的说法时不再给这张卡；没给全要照实说还有几条
    @Test
    void aCardThatRepeatsAShownStatementGivesItsPlaceToAnotherAndTheRestIsCounted() {
        ledger = Ledger.open(dir, Clock.fixed(NOW, ZoneOffset.UTC));
        page("about-me", "关于用户");
        tantalum.memory.DailyNotes daily = new tantalum.memory.DailyNotes(ledger);
        List<String> things = List.of("faucet", "mat", "shelves", "toaster", "kettle", "blender", "fridge", "oven");
        for (int i = 0; i < things.size(); i++) {
            String fact = "Replaced the old kitchen " + things.get(i);
            claim("about-me", fact, 1);
            daily.append("2026-11-15", "s-" + i, "kitchen", "s-" + i + "_1", "10:0" + i, "> by the way I replaced my kitchen " + things.get(i) + "\n- " + fact);
        }
        Recall recall = new Recall(ledger);
        String question = "Which kitchen things have I replaced?";
        Recall.Found six = recall.find(question, "", null, ZoneOffset.UTC, List.of(), new Recall.Room(6, 10_000, 0));
        assertEquals(6, six.hits().size());
        assertTrue(six.hits().stream().allMatch(hit -> hit.item().kind() == MemoryItem.Kind.CLAIM), "six places hold six things, not three things twice");
        assertEquals(2, six.more(), "the other two are counted as left out; the cards that only repeat a statement are not");
        assertEquals(6, six.next());
        assertTrue(RecallText.of(six, ZoneOffset.UTC).contains("2 more notes match and are not shown. This list is not complete"));
        assertTrue(RecallText.of(six, ZoneOffset.UTC, true).contains("call memory_search again with the same query and offset=6"));

        // 接着读：第二页正好是剩下的两条，和第一页不重复，读完就不再说还有
        Recall.Found rest = recall.find(question, "", null, ZoneOffset.UTC, List.of(), new Recall.Room(6, 10_000, six.next()));
        assertEquals(2, rest.hits().size());
        assertEquals(0, rest.more());
        java.util.Set<String> seen = new java.util.HashSet<>();
        six.hits().forEach(hit -> seen.add(hit.item().id()));
        assertTrue(rest.hits().stream().noneMatch(hit -> seen.contains(hit.item().id())), "no statement comes twice");
        assertFalse(RecallText.of(rest, ZoneOffset.UTC, true).contains("not shown"));

        // 按篇幅给：放得下几条给几条，至少一条
        int each = six.hits().getFirst().item().text().length();
        assertEquals(3, recall.find(question, "", null, ZoneOffset.UTC, List.of(), new Recall.Room(30, each * 3 + 5, 0)).hits().size());
        assertEquals(1, recall.find(question, "", null, ZoneOffset.UTC, List.of(), new Recall.Room(30, 1, 0)).hits().size());
        assertEquals(things.size(), recall.find(question, "", null, ZoneOffset.UTC, List.of(), Recall.SEARCH).hits().size(), "the search tool's room holds all eight");
    }
}
