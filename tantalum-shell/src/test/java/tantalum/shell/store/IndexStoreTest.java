package tantalum.shell.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.journal.SessionFileReader;
import tantalum.shell.journal.SessionPaths;
import tantalum.value.event.CompactRequested;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.SessionId;
import tantalum.value.id.SubmissionId;
import tantalum.value.model.TokenUsage;

/** 任务 16、17、20：节流和必写、写失败、用量的真假、启动补齐和重建。 */
class IndexStoreTest {

    private static final Principal ALICE = new Principal("alice");
    private static final Principal BOB = new Principal("bob");
    private static final SessionId S1 = new SessionId("s1");
    private static final SessionId S2 = new SessionId("s2");
    private static final Instant DAY1 = Instant.parse("2026-11-01T23:59:00Z");
    private static final Instant DAY2 = Instant.parse("2026-11-02T00:01:00Z");
    private static final Duration LONG = Duration.ofHours(1);

    @TempDir
    Path dir;

    private SessionPaths paths() {
        return new SessionPaths(dir);
    }

    private static TokenUsage tokens(long input) {
        return new TokenUsage(input, 1, 0, 0);
    }

    private record Recording(List<Integer> batches) implements IndexWriter.Target {
        @Override
        public void write(IndexBatch batch) {
            batches.add(batch.events());
        }
    }

    // ---- 任务 16 ----

    @Test
    void writesOnlyWhenEnoughEventsOrARunEnded() throws Exception {
        List<Integer> batches = new CopyOnWriteArrayList<>();
        try (IndexWriter writer = new IndexWriter(new Recording(batches), 3, LONG, 100)) {
            IndexFixture fixture = new IndexFixture(paths(), writer::onDurable, writer::onStep);
            IndexFixture.Pending pending = fixture.prompt(ALICE, S1, DAY1, "hello");
            Thread.sleep(100);
            assertEquals(List.of(), batches, "two events: below the threshold, no run ended");
            fixture.reply(pending, DAY1, tokens(5));
            waitFor(() -> batches.size() == 1);
            assertEquals(List.of(3), batches, "the third event reaches the threshold and ends the run");

            IndexFixture.Pending second = fixture.prompt(ALICE, S1, DAY1, "again");
            fixture.send(ALICE, S1, second.run(), DAY1, new tantalum.value.event.Cancel(new RequestId("c")));
            waitFor(() -> batches.size() == 2);
            fixture.files.close();
        }
    }

    @Test
    void runEndAloneForcesAWrite() throws Exception {
        List<Integer> batches = new CopyOnWriteArrayList<>();
        try (IndexWriter writer = new IndexWriter(new Recording(batches), 1000, LONG, 2000)) {
            IndexFixture fixture = new IndexFixture(paths(), writer::onDurable, writer::onStep);
            fixture.reply(fixture.prompt(ALICE, S1, DAY1, "hello"), DAY1, tokens(5));
            waitFor(() -> batches.size() == 1);
            assertEquals(List.of(3), batches);
            fixture.files.close();
        }
    }

    @Test
    void intervalFlushesAQuietSession() throws Exception {
        List<Integer> batches = new CopyOnWriteArrayList<>();
        try (IndexWriter writer = new IndexWriter(new Recording(batches), 1000, Duration.ofMillis(50), 2000)) {
            IndexFixture fixture = new IndexFixture(paths(), writer::onDurable, writer::onStep);
            fixture.prompt(ALICE, S1, DAY1, "hello");
            waitFor(() -> batches.size() == 1);
            assertEquals(List.of(2), batches);
            fixture.files.close();
        }
    }

    @Test
    void failedWriteKeepsTheServiceRunningAndIsRetriedWithNothingLost() throws Exception {
        AtomicBoolean failing = new AtomicBoolean(true);
        List<Integer> batches = new CopyOnWriteArrayList<>();
        IndexWriter.Target target = batch -> {
            if (failing.get()) {
                throw new SQLException("disk full");
            }
            batches.add(batch.events());
        };
        try (IndexWriter writer = new IndexWriter(target, 1, Duration.ofMillis(20), 1000)) {
            IndexFixture fixture = new IndexFixture(paths(), writer::onDurable, writer::onStep);
            fixture.reply(fixture.prompt(ALICE, S1, DAY1, "hello"), DAY1, tokens(5));
            writer.flush();
            assertEquals(List.of(), batches);
            failing.set(false);
            writer.flush();
            assertEquals(3, batches.stream().mapToInt(Integer::intValue).sum());
            fixture.files.close();
        }
    }

    /** 放弃之后不能再写位置，否则下次启动发现不了中间的缺口。 */
    @Test
    void givingUpLeavesTheOffsetBehindSoStartupCatchesUp() throws Exception {
        AtomicBoolean failing = new AtomicBoolean(false);
        try (SessionIndex index = SessionIndex.open(dir)) {
            IndexWriter.Target target = batch -> {
                if (failing.get()) {
                    throw new SQLException("disk full");
                }
                index.write(batch);
            };
            IndexFixture fixture;
            try (IndexWriter writer = new IndexWriter(target, 1, Duration.ofMillis(1), 2)) {
                fixture = new IndexFixture(paths(), writer::onDurable, writer::onStep);
                fixture.reply(fixture.prompt(ALICE, S1, DAY1, "first"), DAY1, tokens(5));
                writer.flush();
                long offsetBefore = index.session(S1).orElseThrow().fileOffset();
                failing.set(true);
                IndexFixture.Pending lost = fixture.prompt(ALICE, S1, DAY1, "lost");
                fixture.reply(lost, DAY1, tokens(7));
                writer.flush();
                failing.set(false);
                fixture.reply(fixture.prompt(ALICE, S1, DAY1, "after"), DAY1, tokens(9));
                writer.flush();
                assertEquals(offsetBefore, index.session(S1).orElseThrow().fileOffset());
            }
            fixture.files.close();
            IndexCatchUp.run(index, paths(), new SessionFileReader(EventCodec.withBuiltins()), fixture.kernel);
            assertEquals(Files.size(paths().sessionFile(ALICE, S1)), index.session(S1).orElseThrow().fileOffset());
            assertEquals(List.of(5L, 7L, 9L), index.usage(ALICE).stream().map(row -> row.amount().input()).toList());
        }
    }

    @Test
    void indexFilesArePrivate() throws Exception {
        try (SessionIndex index = SessionIndex.open(dir)) {
            index.claim(S1, ALICE);
            for (String name : List.of(SessionIndex.FILE, SessionIndex.FILE + "-wal")) {
                assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve(name))), name);
            }
        }
    }

    // ---- 任务 17 ----

    private void usageScenario(IndexFixture fixture) throws Exception {
        IndexFixture.Pending first = fixture.prompt(ALICE, S1, DAY1, "one");
        IndexFixture.Pending other = fixture.prompt(ALICE, S2, DAY1, "two");
        fixture.reply(first, DAY1, tokens(10));
        // 跨天：同一个运行开始于第一天，回复在第二天，算第二天。
        fixture.reply(other, DAY2, tokens(100));
        fixture.reply(fixture.prompt(BOB, new SessionId("b1"), DAY1, "bob"), DAY1, tokens(1000));
        // 迟到：运行已经结束后又来一次相同的回复，内核忽略，不能计入。
        fixture.reply(first, DAY2, tokens(99999));
    }

    @Test
    void lateReplyIsNotCountedAndDaysAndSessionsAddUp() throws Exception {
        try (SessionIndex index = SessionIndex.open(dir);
                IndexWriter writer = new IndexWriter(index::write, 1, LONG, 100)) {
            IndexFixture fixture = new IndexFixture(paths(), writer::onDurable, writer::onStep);
            usageScenario(fixture);
            writer.flush();
            assertEquals(List.of(10L, 100L), index.usage(ALICE).stream().map(row -> row.amount().input()).toList());
            UsageLedger ledger = new UsageLedger(index, writer);
            LocalDate day1 = LocalDate.parse("2026-11-01");
            LocalDate day2 = LocalDate.parse("2026-11-02");
            long tenAt = index.usage(ALICE).get(0).seq();
            long hundredAt = index.usage(ALICE).get(1).seq();
            assertEquals(Map.of(), ledger.before(ALICE, tenAt));
            assertEquals(Map.of(day1, tokens(10)), ledger.before(ALICE, hundredAt));
            assertEquals(Map.of(day1, tokens(10), day2, tokens(100)), ledger.before(ALICE, hundredAt + 1));
            assertEquals(fixture.state().principals().get(ALICE).dailyUsage(), ledger.before(ALICE, Long.MAX_VALUE), "same as the kernel's own total");
            assertEquals(Map.of(day1, tokens(1000)), ledger.before(BOB, Long.MAX_VALUE));
            fixture.files.close();
        }
    }

    @Test
    void ledgerSeesUsageStillWaitingInTheQueue() throws Exception {
        try (SessionIndex index = SessionIndex.open(dir);
                IndexWriter writer = new IndexWriter(index::write, 100000, LONG, 200000)) {
            IndexFixture fixture = new IndexFixture(paths(), writer::onDurable, writer::onStep);
            fixture.send(ALICE, S1, null, DAY1, new CompactRequested(new SubmissionId("x"), new RequestId("x"), null));
            IndexFixture.Pending pending = fixture.prompt(ALICE, S2, DAY1, "two");
            fixture.send(ALICE, S2, pending.run(), DAY1, new tantalum.value.event.ModelReplied(pending.command(),
                    List.of(), tantalum.value.model.StopReason.TOOL_USE, "m", tokens(3)));
            assertEquals(3, new UsageLedger(index, writer).before(ALICE, Long.MAX_VALUE).get(LocalDate.parse("2026-11-01")).input());
            fixture.files.close();
        }
    }

    // ---- 任务 20 ----

    private record Dump(List<SessionIndex.Session> sessions, List<IndexChange.Usage> alice, List<IndexChange.Usage> bob, long submissions) {
        static Dump of(SessionIndex index) throws SQLException {
            return new Dump(index.sessions(), index.usage(ALICE), index.usage(BOB), index.submissionCount());
        }
    }

    private IndexFixture finishedScenario(SessionIndex index) throws Exception {
        IndexFixture fixture;
        try (IndexWriter writer = new IndexWriter(index::write, 2, LONG, 1000)) {
            fixture = new IndexFixture(paths(), writer::onDurable, writer::onStep);
            usageScenario(fixture);
            writer.flush();
        }
        fixture.files.close();
        return fixture;
    }

    /** 启动只靠这一列找出要收尾的运行：运行开始时写上，结束时清空，重建后一样。 */
    @Test
    void unfinishedRunsAreRecordedAndClearedWhenTheyEnd() throws Exception {
        List<SessionIndex.Session> before;
        try (SessionIndex index = SessionIndex.open(dir)) {
            IndexFixture fixture;
            IndexFixture.Pending open;
            try (IndexWriter writer = new IndexWriter(index::write, 1, LONG, 1000)) {
                fixture = new IndexFixture(paths(), writer::onDurable, writer::onStep);
                fixture.reply(fixture.prompt(ALICE, S1, DAY1, "done"), DAY1, tokens(1));
                open = fixture.prompt(ALICE, S2, DAY1, "still running");
                writer.flush();
            }
            fixture.files.close();
            List<SessionIndex.Session> unfinished = index.unfinished();
            assertEquals(List.of(S2), unfinished.stream().map(SessionIndex.Session::session).toList());
            assertEquals(open.run(), unfinished.getFirst().activeRun());
            assertNull(index.session(S1).orElseThrow().activeRun());
            before = index.sessions();
        }
        deleteIndex();
        try (SessionIndex rebuilt = SessionIndex.open(dir)) {
            IndexCatchUp.run(rebuilt, paths(), new SessionFileReader(EventCodec.withBuiltins()), IndexFixture.kernel());
            assertEquals(before, rebuilt.sessions());
        }
    }

    @Test
    void rebuildAfterDeletingTheIndexEqualsTheOriginal() throws Exception {
        Dump original;
        try (SessionIndex index = SessionIndex.open(dir)) {
            finishedScenario(index);
            original = Dump.of(index);
            assertEquals(3, original.sessions().size());
            assertEquals(3, original.submissions());
        }
        deleteIndex();
        try (SessionIndex index = SessionIndex.open(dir)) {
            assertEquals(List.of(), index.sessions());
            IndexCatchUp.run(index, paths(), new SessionFileReader(EventCodec.withBuiltins()), IndexFixture.kernel());
            assertEquals(original, Dump.of(index));
        }
    }

    @Test
    void laggingIndexCatchesUpToTheSameResult() throws Exception {
        Dump complete;
        try (SessionIndex index = SessionIndex.open(dir)) {
            finishedScenario(index);
            complete = Dump.of(index);
        }
        // 模拟崩溃：丢掉最后几次写入。文件是全的，索引只有前半。
        deleteIndex();
        try (SessionIndex index = SessionIndex.open(dir);
                var connection = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve(SessionIndex.FILE))) {
            IndexCatchUp.run(index, paths(), new SessionFileReader(EventCodec.withBuiltins()), IndexFixture.kernel());
            try (var statement = connection.createStatement()) {
                statement.execute("UPDATE sessions SET file_offset = 10, run_status = 'stale', last_activity = '2000-01-01T00:00:00Z' WHERE session = 's2'");
                statement.execute("DELETE FROM usage WHERE principal = 'alice' AND input = 100");
                statement.execute("DELETE FROM submissions WHERE request_id = 'req-1'");
            }
            assertFalse(Dump.of(index).equals(complete));
            IndexCatchUp.run(index, paths(), new SessionFileReader(EventCodec.withBuiltins()), IndexFixture.kernel());
            assertEquals(complete, Dump.of(index));
        }
    }

    @Test
    void upToDateFilesAreNotReadAgain() throws Exception {
        try (SessionIndex index = SessionIndex.open(dir)) {
            finishedScenario(index);
            // 文件中间改坏但大小不变。读了的话会跳过它；这里改一个行的标题再补齐，标题不变，说明根本没读。
            Path file = paths().sessionFile(ALICE, S1);
            byte[] bytes = Files.readAllBytes(file);
            bytes[bytes.length / 2] = '#';
            Files.write(file, bytes);
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve(SessionIndex.FILE));
                    var statement = connection.createStatement()) {
                statement.execute("UPDATE sessions SET run_status = 'untouched' WHERE session = 's1'");
            }
            IndexCatchUp.run(index, paths(), new SessionFileReader(EventCodec.withBuiltins()), IndexFixture.kernel());
            assertEquals("untouched", index.session(S1).orElseThrow().runStatus());
        }
    }

    @Test
    void corruptIndexIsRebuiltAndServiceStarts() throws Exception {
        Dump original;
        try (SessionIndex index = SessionIndex.open(dir)) {
            finishedScenario(index);
            original = Dump.of(index);
        }
        deleteSidecars();
        Files.writeString(dir.resolve(SessionIndex.FILE), "this is not a database, and it is long enough to be read as a header");
        try (SessionIndex index = SessionIndex.open(dir)) {
            IndexCatchUp.run(index, paths(), new SessionFileReader(EventCodec.withBuiltins()), IndexFixture.kernel());
            assertEquals(original, Dump.of(index));
        }
    }

    @Test
    void wrongVersionIsRebuilt() throws Exception {
        Dump original;
        try (SessionIndex index = SessionIndex.open(dir)) {
            finishedScenario(index);
            original = Dump.of(index);
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve(SessionIndex.FILE));
                var statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version=99");
        }
        try (SessionIndex index = SessionIndex.open(dir)) {
            assertEquals(List.of(), index.sessions());
            IndexCatchUp.run(index, paths(), new SessionFileReader(EventCodec.withBuiltins()), IndexFixture.kernel());
            assertEquals(original, Dump.of(index));
        }
    }

    @Test
    void corruptSessionFileIsSkippedAndOthersCatchUp() throws Exception {
        try (SessionIndex index = SessionIndex.open(dir)) {
            finishedScenario(index);
        }
        deleteIndex();
        Path bad = paths().sessionFile(ALICE, S1);
        List<String> lines = new ArrayList<>(Files.readAllLines(bad));
        lines.set(1, "{broken");
        Files.write(bad, lines);
        try (SessionIndex index = SessionIndex.open(dir)) {
            IndexCatchUp.run(index, paths(), new SessionFileReader(EventCodec.withBuiltins()), IndexFixture.kernel());
            assertTrue(index.session(S1).isEmpty());
            assertTrue(index.session(S2).isPresent());
            assertTrue(index.session(new SessionId("b1")).isPresent());
        }
    }

    @Test
    void repeatedManualCompactionIsADuplicateAfterRestart() throws Exception {
        SubmissionId compact = new SubmissionId("compact-1");
        RequestId request = new RequestId("compact-request");
        try (IndexWriter writer = new IndexWriter(batch -> { }, 1, LONG, 100)) {
            IndexFixture fixture = new IndexFixture(paths(), writer::onDurable, writer::onStep);
            fixture.send(ALICE, S1, null, DAY1, new CompactRequested(compact, request, null));
            fixture.files.close();
        }
        try (SessionIndex index = SessionIndex.open(dir)) {
            IndexCatchUp.run(index, paths(), new SessionFileReader(EventCodec.withBuiltins()), IndexFixture.kernel());
            tantalum.shell.loop.SubmissionLedger ledger = new tantalum.shell.loop.SubmissionLedger((principal, id) -> {
                try {
                    return index.submission(principal, id);
                } catch (SQLException failure) {
                    throw new IllegalStateException(failure);
                }
            }, 10);
            assertEquals(new tantalum.shell.loop.SubmissionLedger.Claim(compact, false), ledger.claim(ALICE, request));
            assertTrue(ledger.claim(BOB, request).isNew(), "request IDs are per user");
        }
    }

    private void deleteIndex() throws Exception {
        deleteSidecars();
        Files.deleteIfExists(dir.resolve(SessionIndex.FILE));
    }

    private void deleteSidecars() throws Exception {
        Files.deleteIfExists(dir.resolve(SessionIndex.FILE + "-wal"));
        Files.deleteIfExists(dir.resolve(SessionIndex.FILE + "-shm"));
    }

    private interface Condition {
        boolean holds();
    }

    private static void waitFor(Condition condition) throws InterruptedException {
        for (int i = 0; i < 1000 && !condition.holds(); i++) {
            Thread.sleep(5);
        }
        assertTrue(condition.holds());
    }
}
