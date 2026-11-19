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

    /** 对话里说“这条忘掉吧”：只去掉那一句，同一页别的行留着；以后笔记、标题这类原话按它过滤。 */
    @Test
    void takingBackOneStatementKeepsTheRestOfThePage() {
        tantalum.value.id.Principal user = new tantalum.value.id.Principal("u");
        MemoryStores stores = MemoryStoresFixture.of(principal -> dir, MemoryLimits.defaults(), Clock.fixed(Instant.parse("2026-11-16T01:00:00Z"), ZoneOffset.UTC));
        MemoryStore pages = stores.of(user);
        Ledger account = stores.ledger(user);
        pages.write("preferences", "feedback", "How the user wants things done", "跟用户说话要简短\n所有费用按整数计算，不要小数\n");
        new WorkingMemory(account).add(MemoryItem.Kind.TASK, "总运费会产生小数，不符合费用按整数计算的习惯", Map.of(), MemoryItem.Source.ASSISTANT_INFERRED,
                MemoryItem.Actor.AGENT, null, null, false, null);
        MemoryContext context = new MemoryContext(stores);
        String title = "你好，我有个习惯：凡是费用，我都要按整数算，不要小";
        assertFalse(context.hides(user, title, Instant.EPOCH), "control: shown before it is taken back");

        assertTrue(Forgetting.statement(pages, "费用都按整数算，不要小数", MemoryItem.Actor.AGENT).gone() >= 1);
        assertEquals("跟用户说话要简短", pages.read("preferences").orElseThrow().content().strip(), "the other line stays");
        assertTrue(account.items(item -> item.text().contains("整数")).isEmpty(), "nothing alive says it: " + account.items(item -> item.text().contains("整数")));
        assertTrue(Forgetting.forgotten(account).stream().anyMatch(gone -> gone.text().contains("整数")), "and it is remembered as forgotten");
        assertTrue(context.hides(user, title, Instant.EPOCH), "a conversation title that says it is not shown to the model");
    }

    /** 一句短话不能带走整条多行的条目，也不能盖住整张当天卡：按行比，别的行留着（review 2026-11-17）。 */
    @Test
    void oneSentenceDoesNotTakeAWholeMultiLineEntryWithIt() {
        open(Instant.parse("2026-11-16T01:00:00Z"));
        MemoryItem page = claim("project-shop", "商城项目用 PostgreSQL 16\n每周三下午发布\n发布前要跑全量回归\n负责人是小王");
        new DailyNotes(ledger).append("2026-11-15", "s-1", "商城", "r-1", "10:00", "- 商城项目每周三下午发布\n- 用户让把订单页的按钮改成蓝色");

        assertEquals(1, Forgetting.forget(ledger, "每周三下午发布", MemoryItem.Actor.USER));
        MemoryItem left = ledger.get(page.id()).orElseThrow();
        assertEquals("商城项目用 PostgreSQL 16\n发布前要跑全量回归\n负责人是小王", left.text(), "only the line that says it goes");
        assertTrue(left.live());
        assertEquals("- 用户让把订单页的按钮改成蓝色", new DailyNotes(ledger).read("2026-11-15", "s-1").orElseThrow().sections().getFirst().body());
        assertFalse(recalled("商城项目的负责人是谁？").isEmpty(), "the rest of the entry is still found");
    }

    /** 同一轮、同一句原话记出来的别的条目一起忘掉：模型换了个说法写的同一件事，词对不上，但出处是同一句话。 */
    @Test
    void itemsNotedFromTheSameWordsGoTogether() {
        open(Instant.parse("2026-11-16T01:00:00Z"));
        MemoryItem.Provenance from = new MemoryItem.Provenance("s-1", "r-9", null, null);
        MemoryItem plate = ledger.put(MemoryItem.create(ledger.newId(), MemoryItem.Kind.CLAIM, MemoryItem.Source.USER_SAID,
                MemoryItem.Actor.AGENT, "车牌号是 京A-7Q2KD", ledger.clock().instant()).withPage("about-me")
                .withQuote("我的车挂的是京A-7Q2KD").withProvenance(from), 0) instanceof Ledger.Result.Written written ? written.item() : null;
        MemoryItem reworded = new WorkingMemory(ledger).add(MemoryItem.Kind.NOTICED, "座驾登记信息已提供", Map.of(), MemoryItem.Source.USER_SAID,
                MemoryItem.Actor.AGENT, "我的车挂的是 京A-7Q2KD", from, false, null).orElseThrow();
        MemoryItem other = new WorkingMemory(ledger).add(MemoryItem.Kind.TASK, "周五前交季度报告", Map.of(), MemoryItem.Source.USER_SAID,
                MemoryItem.Actor.AGENT, "周五前把季度报告交了", from, false, null).orElseThrow();

        assertEquals(1, Forgetting.forget(ledger, "车牌号 京A-7Q2KD", MemoryItem.Actor.USER));
        assertTrue(ledger.tombstoned(plate.id()));
        assertTrue(ledger.tombstoned(reworded.id()), "noted from the same words in the same turn");
        assertFalse(ledger.tombstoned(other.id()), "another thing said in that turn stays");
    }

    /** 模型替你按一句话忘的时候，你认可过的决定不动、也不被这句话盖住；你自己忘才动。 */
    @Test
    void theModelCannotForgetADecisionYouApproved() {
        tantalum.value.id.Principal user = new tantalum.value.id.Principal("u");
        MemoryStores stores = MemoryStoresFixture.of(principal -> dir, MemoryLimits.defaults(), Clock.fixed(Instant.parse("2026-11-16T01:00:00Z"), ZoneOffset.UTC));
        MemoryStore pages = stores.of(user);
        Ledger account = stores.ledger(user);
        pages.write("decision-database", "project", "数据库选型", "数据库用 PostgreSQL 16");
        MemoryItem decision = account.items(item -> item.kind() == MemoryItem.Kind.CLAIM).getFirst();
        account.put(decision.withStatus(MemoryItem.Status.APPROVED), decision.version());
        pages.write("notes", "project", "杂记", "讨论过数据库用 PostgreSQL 16\n周会改到周四");

        Forgetting.Result result = Forgetting.statement(pages, "数据库用 PostgreSQL 16", MemoryItem.Actor.AGENT);
        assertEquals(1, result.kept().size());
        assertEquals("数据库用 PostgreSQL 16", pages.read("decision-database").orElseThrow().content(), "the approved decision stays");
        assertEquals("周会改到周四", pages.read("notes").orElseThrow().content().strip(), "the unapproved copy is gone");
        MemoryItem approved = account.get(decision.id()).orElseThrow();
        assertFalse(Forgetting.shadowed(Forgetting.forgotten(account), approved), "and recall still brings it back");

        assertEquals(1, Forgetting.forget(account, "数据库用 PostgreSQL 16", MemoryItem.Actor.USER), "the user can");
        assertTrue(pages.read("decision-database").isEmpty());
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

    // ---- 按选中的忘：程序只找候选，选中的才动 ----

    /** 候选只是找出来给人看：不选就什么都不动；只对上个别词的短句也在候选里，但不选它就留着。 */
    @Test
    void candidatesAreOnlyShownAndNothingChangesUntilSomeAreChosen() {
        open(Instant.parse("2026-11-16T01:00:00Z"));
        claim("about-me", "我的车牌号是 京A-7Q2KD。\n我住在朝阳区。");
        claim("preferences", "车牌号这类信息不要写进文档。");
        new DailyNotes(ledger).append("2026-11-15", "s-1", "车", "s-1_1", "10:00", "- 用户的车牌号是 京A-7Q2KD\n- 用户周三去物业");

        List<Forgetting.Place> places = Forgetting.candidates(ledger, "我的车牌号是 京A-7Q2KD");
        List<String> texts = places.stream().map(Forgetting.Place::text).toList();
        assertTrue(texts.contains("我的车牌号是 京A-7Q2KD。") && texts.contains("- 用户的车牌号是 京A-7Q2KD"), texts.toString());
        assertTrue(texts.contains("车牌号这类信息不要写进文档。"), "a loosely related line is offered, not decided: " + texts);
        assertFalse(texts.contains("我住在朝阳区。") || texts.contains("- 用户周三去物业"), texts.toString());
        assertEquals(3, ledger.items(item -> item.text().contains("车牌")).size(), "nothing changed yet");

        List<String> chosen = places.stream().filter(place -> place.text().contains("7Q2KD")).map(Forgetting.Place::id).toList();
        Forgetting.Chosen done = Forgetting.forget(ledger, chosen, MemoryItem.Actor.USER);
        assertEquals(2, done.removed().size(), done.toString());
        assertTrue(done.stale().isEmpty() && done.kept().isEmpty(), done.toString());
        assertTrue(ledger.items(item -> item.text().contains("7Q2KD")).isEmpty());
        assertEquals(1, ledger.items(item -> item.text().contains("我住在朝阳区。")).size(), "the other line of the entry stays");
        assertEquals(1, ledger.items(item -> item.text().contains("车牌号这类信息不要写进文档")).size(), "the line that was not chosen stays");
        assertEquals("- 用户周三去物业", new DailyNotes(ledger).read("2026-11-15", "s-1").orElseThrow().sections().getFirst().body());
    }

    /** 选的时候条目已经变了：不动，告诉调用方重找。你认可过的决定，模型选了也不动，你自己选才动。 */
    @Test
    void aChangedEntryIsLeftAloneAndAnApprovedDecisionOnlyTheUserCanForget() {
        open(Instant.parse("2026-11-16T01:00:00Z"));
        MemoryItem fee = claim("decisions", "加急附加费定为 15。");
        MemoryItem approved = claim("decisions", "数据库用 PostgreSQL。");
        ledger.put(ledger.get(approved.id()).orElseThrow().withStatus(MemoryItem.Status.APPROVED), ledger.get(approved.id()).orElseThrow().version());

        String stalePlace = Forgetting.candidates(ledger, "加急附加费定为 15").getFirst().id();
        ledger.put(ledger.get(fee.id()).orElseThrow().withText("加急附加费定为 18。"), ledger.get(fee.id()).orElseThrow().version());
        Forgetting.Chosen stale = Forgetting.forget(ledger, List.of(stalePlace, "nonsense"), MemoryItem.Actor.USER);
        assertEquals(List.of("nonsense", stalePlace), stale.stale());
        assertTrue(stale.removed().isEmpty());
        assertEquals(1, ledger.items(item -> item.text().contains("定为 18")).size());

        Forgetting.Place decision = Forgetting.candidates(ledger, "数据库用 PostgreSQL").getFirst();
        assertTrue(decision.approved());
        Forgetting.Chosen byAgent = Forgetting.forget(ledger, List.of(decision.id()), MemoryItem.Actor.AGENT);
        assertEquals(List.of(decision.id()), byAgent.kept());
        assertEquals(1, ledger.items(item -> item.text().contains("PostgreSQL")).size());
        Forgetting.Chosen byUser = Forgetting.forget(ledger, List.of(decision.id()), MemoryItem.Actor.USER);
        assertEquals(List.of("数据库用 PostgreSQL。"), byUser.removed());
        assertTrue(ledger.items(item -> item.text().contains("PostgreSQL")).isEmpty());
    }
}
