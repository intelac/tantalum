package tantalum.memory.recall;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.memory.MemoryLimits;
import tantalum.memory.MemoryStore;
import tantalum.memory.MemoryStores;
import tantalum.memory.MemoryStoresFixture;
import tantalum.memory.ledger.Ledger;
import tantalum.memory.ledger.MemoryItem;
import tantalum.value.id.Principal;

/**
 * 按意思找（memory-final-plan 第 9 节第 3 步）。这里的假向量服务把一句话变成“说的是哪个主题”的向量（车、卧室、别的），
 * 所以“我的座驾挂的什么牌”和“车牌号是 7Q2KD”一个词都不重合也是同一个方向。测的是程序这一段：向量跟上账、存盘再读、
 * 门槛、两路合并、范围和取代照旧算数、服务给不出来时退回关键词。向量模型本身好不好不在这里测。
 */
class VectorsTest {

    private static final Instant NOW = Instant.parse("2026-11-19T01:00:00Z");
    private static final Principal ALICE = new Principal("alice");

    @TempDir
    Path dir;

    /** 主题向量：车、卧室、其他。带一点点另外两个方向，免得正好是 0。 */
    private static float[] topic(String text) {
        float car = text.contains("车") || text.contains("座驾") || text.contains("7Q2KD") ? 1 : 0.05f;
        float room = text.contains("卧室") || text.contains("床") ? 1 : 0.05f;
        float other = car < 1 && room < 1 ? 1 : 0.05f;
        float norm = (float) Math.sqrt(car * car + room * room + other * other);
        return new float[] {car / norm, room / norm, other / norm};
    }

    private final List<String> embedded = new ArrayList<>();
    private boolean up = true;
    private final Vectors.Embedder embedder = (texts, query) -> {
        if (!up) {
            return Optional.empty();
        }
        embedded.addAll(texts);
        return Optional.of(texts.stream().map(VectorsTest::topic).toList());
    };

    private Ledger ledger;
    private MemoryStore store;

    private MemoryItem claim(String page, String text) {
        return store.addClaim(page, "person", "about " + page, MemoryItem.create(ledger.newId(), MemoryItem.Kind.CLAIM,
                MemoryItem.Source.USER_SAID, MemoryItem.Actor.USER, text, ledger.clock().instant())).orElseThrow();
    }

    private void open() {
        ledger = Ledger.open(dir, Clock.fixed(NOW, ZoneOffset.UTC));
        store = new MemoryStore(ledger, MemoryLimits.defaults());
    }

    @Test
    void vectorsFollowTheLedgerSurviveAReloadAndAreRecomputedOnlyWhenTheTextChanges() {
        open();
        MemoryItem plate = claim("car", "车牌号是 7Q2KD");
        MemoryItem room = claim("home", "卧室朝北，床靠窗");
        Vectors vectors = new Vectors(dir);
        Recall recall = new Recall(ledger);

        assertEquals(2, vectors.sync(recall.embeddable(), embedder, 64));
        assertEquals(0, vectors.sync(recall.embeddable(), embedder, 64), "nothing changed, nothing asked again");
        assertEquals(2, embedded.size());
        assertTrue(Files.exists(dir.resolve(Vectors.FILE)));

        Vectors reloaded = new Vectors(dir);
        assertEquals(2, reloaded.size());
        List<Vectors.Scored> near = reloaded.nearest(topic("我的座驾挂的什么牌"), 5, Semantic.FLOOR);
        assertEquals(List.of(plate.id()), near.stream().map(Vectors.Scored::id).toList(), "only the one about the car clears the floor");
        assertTrue(reloaded.nearest(new float[] {1, 0}, 5, 0).isEmpty(), "vectors of another size (another model) are not compared");

        // 文字变了重算；条目没了丢掉
        ledger.put(ledger.get(room.id()).orElseThrow().withText("卧室改到朝南那间了"), ledger.get(room.id()).orElseThrow().version());
        store.delete("car", MemoryItem.Actor.USER);
        assertEquals(1, reloaded.sync(recall.embeddable(), embedder, 64));
        assertEquals(1, reloaded.size());
        assertEquals("卧室改到朝南那间了", embedded.getLast());

        // 服务给不出来：这次不算，已有的照用
        up = false;
        claim("home", "床垫是硬的");
        assertEquals(0, reloaded.sync(recall.embeddable(), embedder, 64));
        assertEquals(1, reloaded.size());
    }

    @Test
    void whatMeaningFindsIsMergedWithWhatKeywordsFindAndScopeStillApplies() {
        open();
        MemoryItem plate = claim("car", "车牌号是 7Q2KD");
        MemoryItem garage = claim("garage", "停车位在 B2 层 117 号");
        claim("home", "卧室朝北，床靠窗");
        Recall recall = new Recall(ledger);
        Vectors vectors = new Vectors(dir);
        vectors.sync(recall.embeddable(), embedder, 64);
        String question = "我的座驾挂的什么牌";

        assertTrue(recall.search(question, "", null, ZoneOffset.UTC).isEmpty(), "control: no word in common, keywords find nothing");
        List<Vectors.Scored> near = vectors.nearest(topic(question), Semantic.NEAR, Semantic.FLOOR);
        List<String> found = recall.search(question, "", null, ZoneOffset.UTC, near).stream().map(hit -> hit.item().id()).toList();
        assertTrue(found.contains(plate.id()) && found.contains(garage.id()), "found by meaning: " + found);
        assertEquals(2, found.size(), "the bedroom is not about the car");

        // 两路都找到的排在只有一路找到的前面
        List<String> both = recall.search("停车位在哪", "", null, ZoneOffset.UTC, near).stream().map(hit -> hit.item().id()).toList();
        assertEquals(garage.id(), both.getFirst(), "keywords and meaning agree on it");
        assertTrue(both.contains(plate.id()));

        // 已经不在账上的（忘掉了）向量还没跟上时也不会被带回来：只认当前的候选
        store.delete("car", MemoryItem.Actor.USER);
        assertFalse(recall.search(question, "", null, ZoneOffset.UTC, near).stream().anyMatch(hit -> hit.item().id().equals(plate.id())));
    }

    @Test
    void lookingByMeaningCatchesUpInTheBackgroundAndFallsBackWhenTheServiceIsSlowOrAbsent() throws Exception {
        MemoryStores stores = MemoryStoresFixture.of(principal -> dir, MemoryLimits.defaults(), Clock.fixed(NOW, ZoneOffset.UTC));
        ledger = stores.ledger(ALICE);
        store = stores.of(ALICE);
        MemoryItem plate = claim("car", "车牌号是 7Q2KD");
        try (Semantic semantic = new Semantic(stores, user -> embedder, java.time.Duration.ofSeconds(10))) {
            // 第一次：已有的记忆还没有向量，先等它们算出来，这一次就按意思找得到
            assertEquals(List.of(plate.id()), semantic.near(ALICE, "我的座驾挂的什么牌").stream().map(Vectors.Scored::id).toList());
            // 之后新记的在后台跟上
            MemoryItem garage = claim("garage", "车位在 B2 层");
            semantic.near(ALICE, "我的座驾挂的什么牌");
            semantic.drain();
            assertTrue(semantic.near(ALICE, "我的座驾挂的什么牌").stream().anyMatch(scored -> scored.id().equals(garage.id())));
            assertTrue(semantic.near(ALICE, " ").isEmpty());
            semantic.drain();
            up = false;
            assertTrue(semantic.near(ALICE, "我的座驾挂的什么牌").isEmpty(), "no service, no vectors: the caller goes on with keywords");
        }
        try (Semantic slow = new Semantic(stores, user -> (texts, query) -> {
            try {
                Thread.sleep(query ? Semantic.QUERY_MILLIS + 1500 : 0);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            }
            return Optional.of(texts.stream().map(VectorsTest::topic).toList());
        }, java.time.Duration.ZERO)) {
            long started = System.nanoTime();
            assertTrue(slow.near(ALICE, "我的座驾挂的什么牌").isEmpty(), "a slow service does not hold up the answer");
            assertTrue((System.nanoTime() - started) / 1_000_000 < Semantic.QUERY_MILLIS + 1000);
        }
    }
}
