package tantalum.memory.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** memory-final-plan 第 3、4 节：记忆账只追加、写前比对版本、墓碑、半行截掉、全部字段原样读回。 */
class LedgerTest {

    private static final Instant T0 = Instant.parse("2026-11-16T01:00:00Z");

    @TempDir
    Path dir;

    private final Clock clock = Clock.fixed(T0, ZoneOffset.UTC);

    private MemoryItem task(Ledger ledger, String title) {
        return MemoryItem.create(ledger.newId(), MemoryItem.Kind.TASK, MemoryItem.Source.USER_SAID, MemoryItem.Actor.AGENT, title, T0)
                .withAttrs(Map.of("title", title, "status", "active"));
    }

    @Test
    void writesAreVersionedAndAStaleVersionIsRefusedWithTheCurrentValue() {
        Ledger ledger = Ledger.open(dir, clock);
        MemoryItem first = assertInstanceOf(Ledger.Result.Written.class, ledger.put(task(ledger, "记忆方案"), 0)).item();
        assertEquals(1, first.version());
        MemoryItem second = assertInstanceOf(Ledger.Result.Written.class, ledger.put(first.withText("记忆方案 v3"), 1)).item();
        assertEquals(2, second.version());
        assertEquals(first.created(), second.created());
        Ledger.Result stale = ledger.put(first.withText("另一个会话的旧版本"), 1);
        assertEquals("记忆方案 v3", assertInstanceOf(Ledger.Result.Conflict.class, stale).current().text());
        assertInstanceOf(Ledger.Result.Conflict.class, ledger.put(task(ledger, "新的"), 3), "a new item starts at 0");
    }

    @Test
    void tombstonedItemsAreHiddenButStillKnownAndCannotBeWrittenAgain() {
        Ledger ledger = Ledger.open(dir, clock);
        MemoryItem item = assertInstanceOf(Ledger.Result.Written.class, ledger.put(task(ledger, "x"), 0)).item();
        assertTrue(ledger.tombstone(item.id(), MemoryItem.Actor.USER, "forget"));
        assertFalse(ledger.tombstone(item.id(), MemoryItem.Actor.USER, "again"));
        assertTrue(ledger.items(any -> true).isEmpty());
        assertEquals(1, ledger.everything(any -> true).size());
        assertNull(assertInstanceOf(Ledger.Result.Conflict.class, ledger.put(item.withText("y"), 1)).current());
        Ledger reopened = Ledger.open(dir, clock);
        assertTrue(reopened.tombstoned(item.id()));
        assertTrue(reopened.items(any -> true).isEmpty());
    }

    @Test
    void everyFieldSurvivesARoundTrip() {
        Ledger ledger = Ledger.open(dir, clock);
        MemoryItem full = new MemoryItem(ledger.newId(), 1, MemoryItem.State.PENDING, MemoryItem.Kind.CLAIM, "tantalum-release",
                "project:/repo/kyagent", MemoryItem.Source.TOOL_WEB, "第一个版本叫 0.1",
                new MemoryItem.Provenance("s-1", "s-1_3", "c-7", 42L), MemoryItem.Actor.CHILD, T0, "m-other", T0.plusSeconds(5),
                MemoryItem.Confidence.INFERRED, List.of("m-a", "m-b"), MemoryItem.Status.APPROVED, true, "版本叫 0.1",
                Map.of("z", "last", "a", "first"), "team-a", List.of("bob"), "sha256:x", "yes", T0, T0);
        MemoryItem written = assertInstanceOf(Ledger.Result.Written.class, ledger.put(full, 0)).item();
        assertEquals(written, Ledger.open(dir, clock).get(full.id()).orElseThrow());
    }

    @Test
    void aPartialLastLineIsCutAndABadLineInTheMiddleIsSkipped() throws Exception {
        Ledger ledger = Ledger.open(dir, clock);
        ledger.put(task(ledger, "a"), 0);
        Files.writeString(dir.resolve(Ledger.FILE), "not json\n", StandardOpenOption.APPEND);
        Ledger again = Ledger.open(dir, clock);
        again.put(task(again, "b"), 0);
        Files.writeString(dir.resolve(Ledger.FILE), "{\"put\":{\"id\":", StandardOpenOption.APPEND);
        Ledger last = Ledger.open(dir, clock);
        assertEquals(List.of("a", "b"), last.items(any -> true).stream().map(MemoryItem::text).toList());
        assertTrue(Files.readString(dir.resolve(Ledger.FILE), StandardCharsets.UTF_8).endsWith("\n"));
        last.put(task(last, "c"), 0);
        assertEquals(3, Ledger.open(dir, clock).items(any -> true).size());
    }

    @Test
    void markersAreWrittenOnceAndAFreshLedgerCannotOverwriteAnExistingOne() {
        Ledger ledger = Ledger.open(dir, clock);
        ledger.mark("imported-v1");
        ledger.mark("imported-v1");
        assertEquals(List.of("imported-v1"), Ledger.open(dir, clock).markers());
        assertThrows(IllegalStateException.class, () -> Ledger.writeFresh(dir, List.of()));
    }
}
