package tantalum.shell.store;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.journal.SessionFileReader;
import tantalum.shell.journal.SessionPaths;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;
import tantalum.value.model.ReasoningBlock;
import tantalum.value.model.TextBlock;

/** session-find-pin R3：索引存用户消息和回复的文字，按内容搜会话。 */
class SessionSearchTest {

    private static final Principal YI = new Principal("yi");
    private static final Principal BOB = new Principal("bob");
    private static final SessionId S1 = new SessionId("s1");
    private static final SessionId S2 = new SessionId("s2");
    private static final SessionId S3 = new SessionId("s3");
    private static final Instant T1 = Instant.parse("2026-11-10T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-11-10T11:00:00Z");
    private static final Instant T3 = Instant.parse("2026-11-10T12:00:00Z");

    @TempDir
    Path dir;

    private void scenario(SessionIndex index) throws Exception {
        IndexFixture fixture;
        try (IndexWriter writer = new IndexWriter(index::write, 1, Duration.ofHours(1), 1000)) {
            fixture = new IndexFixture(new SessionPaths(dir), writer::onDurable, writer::onStep);
            fixture.reply(fixture.prompt(YI, S1, T1, "Why does the Order total go negative?"),
                    T1, List.of(new ReasoningBlock("anthropic", new Json.Str("hidden musing"), "claude", "m"),
                            new TextBlock("Because the discount is applied twice in 订单服务."), new TextBlock("Fix it in place().")));
            fixture.reply(fixture.prompt(YI, S2, T2, "50% off_sale rule"), T2, List.of(new TextBlock("noted")));
            fixture.reply(fixture.prompt(YI, S3, T3, "the order of imports"), T3, List.of(new TextBlock("alphabetical")));
            fixture.reply(fixture.prompt(BOB, new SessionId("b1"), T3, "order lunch"), T3, List.of(new TextBlock("ok")));
            writer.flush();
        }
        fixture.files.close();
    }

    private static List<String> sessions(List<SessionIndex.TextHit> hits) {
        return hits.stream().map(hit -> hit.session().value()).toList();
    }

    @Test
    void userAndAssistantTextIsFoundNewestSessionFirstOneHitEach() throws Exception {
        try (SessionIndex index = SessionIndex.open(dir)) {
            scenario(index);
            assertEquals(List.of("s3", "s1"), sessions(index.search(YI, "ORDER", 20)), "ASCII case does not matter; bob's are not seen");
            assertEquals(List.of("s3"), sessions(index.search(YI, "order", 1)));
            List<SessionIndex.TextHit> chinese = index.search(YI, "订单", 20);
            assertEquals(List.of("s1"), sessions(chinese), "two Chinese characters are enough");
            assertEquals("assistant", chinese.getFirst().role());
            assertEquals("Because the discount is applied twice in 订单服务.\nFix it in place().", chinese.getFirst().text());
            assertEquals("user", index.search(YI, "negative", 20).getFirst().role());
            assertEquals(List.of(), index.search(YI, "hidden musing", 20), "reasoning is not searched");
        }
    }

    @Test
    void wildcardsAreLiteral() throws Exception {
        try (SessionIndex index = SessionIndex.open(dir)) {
            scenario(index);
            assertEquals(List.of("s2"), sessions(index.search(YI, "50%", 20)));
            assertEquals(List.of("s2"), sessions(index.search(YI, "off_sale", 20)));
            assertEquals(List.of(), sessions(index.search(YI, "off%sale", 20)));
            assertEquals(List.of(), sessions(index.search(YI, "o_der", 20)));
        }
    }

    @Test
    void aRebuildFromTheSessionFilesFindsTheSame() throws Exception {
        List<SessionIndex.TextHit> original;
        try (SessionIndex index = SessionIndex.open(dir)) {
            scenario(index);
            original = index.search(YI, "e", 20);
        }
        for (String name : List.of(SessionIndex.FILE, SessionIndex.FILE + "-wal", SessionIndex.FILE + "-shm")) {
            Files.deleteIfExists(dir.resolve(name));
        }
        try (SessionIndex rebuilt = SessionIndex.open(dir)) {
            IndexCatchUp.run(rebuilt, new SessionPaths(dir), new SessionFileReader(EventCodec.withBuiltins()));
            IndexCatchUp.run(rebuilt, new SessionPaths(dir), new SessionFileReader(EventCodec.withBuiltins()));
            assertEquals(original, rebuilt.search(YI, "e", 20));
        }
    }

    @Test
    void theSnippetCentresOnTheMatch() {
        assertEquals("short text", Snippet.around("short\n  text", "TEXT", 120));
        String long_ = "a".repeat(100) + " needle " + "b".repeat(100);
        String snippet = Snippet.around(long_, "Needle", 20);
        assertEquals("…aaaaaa needle bbbbbb…", snippet);
        assertEquals("aaaaaaaaaaaaaaaaaaaa…", Snippet.around(long_, "missing", 20));
        assertEquals("…xxxxxxxend", Snippet.around("x".repeat(50) + "end", "end", 10), "a match at the end keeps the full width");
        assertEquals("😀😀😀😀😀…", Snippet.around("😀".repeat(30), "x", 5), "emoji are not cut in half");
    }
}
