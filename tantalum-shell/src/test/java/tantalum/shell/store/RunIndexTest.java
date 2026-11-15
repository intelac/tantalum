package tantalum.shell.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.journal.SessionFileReader;
import tantalum.shell.journal.SessionPaths;
import tantalum.shell.trace.RunRow;
import tantalum.shell.trace.Tally;
import tantalum.shell.trace.Trace;
import tantalum.shell.trace.TraceRecord;
import tantalum.shell.trace.TraceRecord.Head;
import tantalum.shell.trace.TraceRecord.RunHead;
import tantalum.shell.trace.TraceRecord.UserInput;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;
import tantalum.value.model.TokenUsage;
import tantalum.value.state.EndReason;

/** 运行表（run-tree 需求 3、4）：实时写入和启动补齐结果相同，重启后结束的运行重算，筛选、排序和翻页。 */
class RunIndexTest {

    private static final Principal YI = new Principal("yi");
    private static final Principal BOB = new Principal("bob");
    private static final SessionId S1 = new SessionId("s1");
    private static final SessionId S2 = new SessionId("s2");
    private static final Instant T1 = Instant.parse("2026-11-10T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-11-10T11:00:00Z");
    private static final Instant T3 = Instant.parse("2026-11-10T12:00:00Z");

    @TempDir
    Path dir;

    private static RunTable.Filter all() {
        return new RunTable.Filter(null, null, null, null, null, null, null, RunTable.Sort.STARTED, null, 50);
    }

    /** 两个用户、三个会话；s2 的运行还没结束。 */
    private IndexFixture scenario(IndexWriter writer) throws Exception {
        IndexFixture fixture = new IndexFixture(new SessionPaths(dir), writer::onDurable, writer::onStep);
        fixture.reply(fixture.prompt(YI, S1, T1, "hello   world\n again"), T1.plusSeconds(3), new TokenUsage(100, 10, 0, 0));
        fixture.reply(fixture.prompt(YI, S1, T2, "second"), T2.plusSeconds(1), new TokenUsage(300, 30, 50, 0));
        fixture.prompt(YI, S2, T3, "still going");
        fixture.reply(fixture.prompt(BOB, new SessionId("b1"), T3, "bob's"), T3.plusSeconds(1), new TokenUsage(1, 1, 0, 0));
        return fixture;
    }

    /** 像净室一样：每一步的轨迹记录整批交给收集器。 */
    private static void live(IndexFixture fixture, RunCollector collector, int from) {
        Trace trace = new Trace();
        for (int i = 0; i < fixture.steps.size(); i++) {
            IndexFixture.Stepped step = fixture.steps.get(i);
            List<TraceRecord> records = trace.apply(step.envelope(), step.before(), step.decision());
            if (i >= from) {
                collector.onRecords(step.envelope().seq(), step.envelope().principal(), records);
            }
        }
    }

    @Test
    void liveRowsEqualTheRowsCatchUpComputesFromTheFiles() throws Exception {
        List<RunTable.Listed> liveRows;
        try (SessionIndex index = SessionIndex.open(dir)) {
            IndexFixture fixture;
            try (IndexWriter writer = new IndexWriter(index::write, 1, Duration.ofHours(1), 1000)) {
                fixture = scenario(writer);
                RunCollector collector = new RunCollector(writer::onRun, (owner, session, run) -> { throw new AssertionError("no rebuild"); });
                live(fixture, collector, 0);
                assertEquals(1, collector.openRuns(), "only the run in s2 is still open");
                writer.flush();
            }
            fixture.files.close();
            liveRows = index.runs(YI, all()).runs();
        }
        Path rebuilt = Files.createDirectory(dir.resolve("rebuilt"));
        try (SessionIndex index = SessionIndex.open(rebuilt)) {
            IndexCatchUp.run(index, new SessionPaths(dir), new SessionFileReader(EventCodec.withBuiltins()));
            assertEquals(liveRows, index.runs(YI, all()).runs());
        }
        assertEquals(List.of("still going", "second", "hello world again"), liveRows.stream().map(item -> item.row().prompt()).toList());
        RunRow open = liveRows.getFirst().row();
        assertNull(open.end());
        assertNull(open.endReason());
        RunRow second = liveRows.get(1).row();
        assertEquals(EndReason.COMPLETED, second.endReason());
        assertEquals(new TokenUsage(300, 30, 50, 0), second.tally().tokens());
        assertEquals(350L, second.tally().context());
        assertEquals(List.of("m"), second.tally().models());
        assertEquals(1_000L, second.tally().ms());
        assertTrue(liveRows.get(2).title().startsWith("hello"), "the session title comes along: " + liveRows.get(2).title());
    }

    @Test
    void filtersSortsAndPages() throws Exception {
        try (SessionIndex index = SessionIndex.open(dir)) {
            IndexFixture fixture;
            try (IndexWriter writer = new IndexWriter(index::write, 1, Duration.ofHours(1), 1000)) {
                fixture = scenario(writer);
                live(fixture, new RunCollector(writer::onRun, (owner, session, run) -> { }), 0);
                writer.flush();
            }
            fixture.files.close();
            RunTable.Filter base = all();
            assertEquals(3, index.runs(YI, base).runs().size(), "bob's run is not listed");
            assertEquals(1, index.runs(BOB, base).runs().size());
            assertEquals(List.of("still going"), prompts(index.runs(YI, with(base, Set.of(RunTable.RUNNING), null))));
            assertEquals(List.of("second", "hello world again"), prompts(index.runs(YI, with(base, Set.of("COMPLETED"), null))));
            assertEquals(List.of("still going"), prompts(index.runs(YI, new RunTable.Filter(null, null, null, null, null, null, S2,
                    RunTable.Sort.STARTED, null, 50))));
            assertEquals(List.of("second", "hello world again"), prompts(index.runs(YI, new RunTable.Filter(null, null, null, null, null, "m", null,
                    RunTable.Sort.STARTED, null, 50))), "the open run has no reply yet, so no model");
            assertEquals(List.of("hello world again"), prompts(index.runs(YI, new RunTable.Filter(T1, T2, null, null, null, null, null,
                    RunTable.Sort.STARTED, null, 50))), "from is inclusive, to exclusive");
            assertEquals("second", prompts(index.runs(YI, new RunTable.Filter(null, null, null, null, null, null, null,
                    RunTable.Sort.TOKENS, null, 50))).getFirst());
            assertEquals("hello world again", prompts(index.runs(YI, new RunTable.Filter(null, null, null, null, null, null, null,
                    RunTable.Sort.MS, null, 50))).getFirst(), "3 s is the slowest; the open run has no duration and sorts last");
            assertEquals(List.of(), prompts(index.runs(YI, new RunTable.Filter(null, null, null, true, null, null, null,
                    RunTable.Sort.STARTED, null, 50))));
            assertEquals(List.of(), prompts(index.runs(YI, new RunTable.Filter(null, null, null, null, "bash", null, null,
                    RunTable.Sort.STARTED, null, 50))));

            List<String> paged = new ArrayList<>();
            String cursor = null;
            do {
                RunTable.Page page = index.runs(YI, new RunTable.Filter(null, null, null, null, null, null, null, RunTable.Sort.STARTED, cursor, 1));
                paged.addAll(prompts(page));
                cursor = page.next();
            } while (cursor != null);
            assertEquals(List.of("still going", "second", "hello world again"), paged);
            assertThrows(IllegalArgumentException.class, () -> index.runs(YI, new RunTable.Filter(null, null, null, null, null, null, null,
                    RunTable.Sort.STARTED, "not-a-cursor!", 1)));
        }
    }

    @Test
    void aRunThatStartedBeforeARestartIsRebuiltFromTheFileWhenItEnds() throws Exception {
        try (SessionIndex index = SessionIndex.open(dir)) {
            IndexFixture fixture;
            try (IndexWriter writer = new IndexWriter(index::write, 1, Duration.ofHours(1), 1000)) {
                fixture = new IndexFixture(new SessionPaths(dir), writer::onDurable, writer::onStep);
                IndexFixture.Pending pending = fixture.prompt(YI, S1, T1, "across a restart");
                int restartAt = fixture.steps.size();
                fixture.reply(pending, T1.plusSeconds(2), new TokenUsage(70, 7, 0, 0));
                List<RunId> rebuilt = new ArrayList<>();
                List<RunRow> emitted = new ArrayList<>();
                // 重启后的收集器只看到结束那一步
                live(fixture, new RunCollector(emitted::add, (owner, session, run) -> rebuilt.add(run)), restartAt);
                assertEquals(List.of(), emitted, "no partial row");
                assertEquals(List.of(pending.run()), rebuilt);
                fixture.files.close();
                RunRow row = RunRebuild.replayRun(new SessionPaths(dir).sessionFile(YI, S1),
                        new RecordReplay(new SessionPaths(dir), new SessionFileReader(EventCodec.withBuiltins())), YI, pending.run());
                assertNotNull(row);
                assertEquals("across a restart", row.prompt());
                assertEquals(new TokenUsage(70, 7, 0, 0), row.tally().tokens());
                assertEquals(EndReason.COMPLETED, row.endReason());
            }
        }
    }

    @Test
    void aBatchWhoseRunHeadIsNotFirstStillStartsTheRun() {
        RunId run = new RunId("s1_5");
        Head head = new Head("h", "5.1", run, 1, null, null, T1, null);
        List<RunRow> emitted = new ArrayList<>();
        RunCollector collector = new RunCollector(emitted::add, (owner, session, id) -> { throw new AssertionError("no rebuild"); });
        collector.onRecords(5, YI, List.of(
                new UserInput(new Head("u", "5.0", run, 1, null, null, T1, T1), UserInput.Mode.PROMPT, "hi", TraceRecord.Source.USER),
                new RunHead(head, RunHead.Trigger.PROMPT, "RUNNING", null, null)));
        assertEquals(1, emitted.size());
        assertEquals("hi", emitted.getFirst().prompt());
        // 结束和同一步的最后一条记录一起到，运行头排在前面：最终行要包括那条记录
        collector.onRecords(6, YI, List.of(
                new RunHead(head.ended(T2), RunHead.Trigger.PROMPT, "IDLE", EndReason.COMPLETED, null),
                new UserInput(new Head("u2", "6.0", run, 1, null, null, T1, T1), UserInput.Mode.STEER, "more", TraceRecord.Source.USER)));
        assertEquals(2, emitted.size());
        assertEquals(EndReason.COMPLETED, emitted.getLast().endReason());
        assertEquals(0, collector.openRuns());
    }

    @Test
    void theSummaryCountsSplitsDaysByZoneAndListsFacets() throws Exception {
        try (SessionIndex index = SessionIndex.open(dir)) {
            assertTrue(index.claim(S1, YI));
            IndexBatch batch = new IndexBatch();
            // 东京时间 11-10 08:30 是 UTC 11-09 23:30：按 UTC 和按东京分在不同的天
            Instant lateUtc = Instant.parse("2026-11-09T23:30:00Z");
            batch.add(row("s1_1", lateUtc, 1_000L, EndReason.COMPLETED, 10, 0, List.of("bash"), List.of("local/qwen")));
            batch.add(row("s1_2", T1, 3_000L, EndReason.FAILED, 20, 1, List.of("read_file"), List.of("claude/x")));
            batch.add(row("s1_3", T2, 2_000L, EndReason.COMPLETED, 30, 0, List.of("bash", "read_file"), List.of("local/qwen")));
            batch.add(row("s1_4", T3, null, null, 40, 0, List.of(), List.of()));
            index.write(batch);

            RunTable.Summary all = index.runSummary(YI, all(), java.time.ZoneOffset.UTC);
            assertEquals(4, all.runs());
            assertEquals(java.util.Map.of("COMPLETED", 2, "FAILED", 1, RunTable.RUNNING, 1), all.ends());
            assertEquals(1, all.toolErrorRuns());
            assertEquals(100L, all.tokens());
            assertEquals(2_000L, all.p50Ms(), "nearest rank over the three ended runs");
            assertEquals(3_000L, all.p90Ms());
            assertEquals(List.of("2026-11-09", "2026-11-10"), all.days().stream().map(day -> day.day().toString()).toList());
            assertEquals(List.of("bash", "read_file"), all.tools());
            assertEquals(List.of("claude/x", "local/qwen"), all.models());

            RunTable.Summary tokyo = index.runSummary(YI, all(), java.time.ZoneId.of("Asia/Tokyo"));
            assertEquals(List.of("2026-11-10"), tokyo.days().stream().map(day -> day.day().toString()).toList());
            assertEquals(100L, tokyo.days().getFirst().tokens());

            // 按工具筛：数字跟着变，清单不变
            RunTable.Summary bash = index.runSummary(YI, new RunTable.Filter(null, null, null, null, "bash", null, null,
                    RunTable.Sort.STARTED, null, 50), java.time.ZoneOffset.UTC);
            assertEquals(2, bash.runs());
            assertEquals(List.of("bash", "read_file"), bash.tools());
            assertEquals(List.of("claude/x", "local/qwen"), bash.models());

            RunTable.Summary none = index.runSummary(BOB, all(), java.time.ZoneOffset.UTC);
            assertEquals(0, none.runs());
            assertNull(none.p50Ms());
        }
    }

    @Test
    void theSummaryOfTenThousandRunsIsFast() throws Exception {
        try (SessionIndex index = SessionIndex.open(dir)) {
            assertTrue(index.claim(S1, YI));
            IndexBatch batch = new IndexBatch();
            for (int i = 0; i < 10_000; i++) {
                batch.add(row("s1_" + i, T1.plusSeconds(i * 60L), (long) i, EndReason.COMPLETED, i, 0, List.of("bash"), List.of("local/qwen")));
            }
            index.write(batch);
            index.runSummary(YI, all(), java.time.ZoneOffset.UTC);
            long start = System.nanoTime();
            RunTable.Summary summary = index.runSummary(YI, all(), java.time.ZoneId.of("Asia/Tokyo"));
            long millis = (System.nanoTime() - start) / 1_000_000;
            assertEquals(10_000, summary.runs());
            assertTrue(millis < 200, "summary took " + millis + " ms");
        }
    }

    private static RunRow row(String run, Instant start, Long ms, EndReason end, long tokens, int toolErrors, List<String> tools,
                              List<String> models) {
        Tally tally = new Tally(new TokenUsage(tokens, 0, 0, 0), TokenUsage.ZERO, null, 1, 1, 0, null, null, tools.size(), toolErrors, null,
                tools.stream().map(name -> new Tally.ToolCount(name, 1, 0)).toList(), 0, 0, models, ms);
        return new RunRow(YI, S1, new RunId(run), 1, RunHead.Trigger.PROMPT, start, ms == null ? null : start.plusMillis(ms), end, "p", tally);
    }

    @Test
    void theFirstPageOfTenThousandRunsIsFast() throws Exception {
        try (SessionIndex index = SessionIndex.open(dir)) {
            assertTrue(index.claim(S1, YI));
            IndexBatch batch = new IndexBatch();
            Tally tally = new Tally(new TokenUsage(1, 1, 0, 0), TokenUsage.ZERO, 2L, 1, 1, 0, 5L, 1L, 2, 0, 3L,
                    List.of(new Tally.ToolCount("bash", 2, 0)), 0, 0, List.of("local/qwen"), 10L);
            for (int i = 0; i < 10_000; i++) {
                batch.add(new RunRow(YI, S1, new RunId("s1_" + i), i, RunHead.Trigger.PROMPT, T1.plusSeconds(i), T1.plusSeconds(i + 1),
                        EndReason.COMPLETED, "p" + i, tally));
            }
            index.write(batch);
            index.runs(YI, all());
            long start = System.nanoTime();
            RunTable.Page page = index.runs(YI, new RunTable.Filter(null, null, null, null, "bash", null, null, RunTable.Sort.STARTED, null, 50));
            long millis = (System.nanoTime() - start) / 1_000_000;
            assertEquals(50, page.runs().size());
            assertEquals("p9999", page.runs().getFirst().row().prompt());
            assertEquals(List.of(new Tally.ToolCount("bash", 2, 0)), page.runs().getFirst().row().tally().tools());
            assertTrue(millis < 100, "first page took " + millis + " ms");
        }
    }

    private static RunTable.Filter with(RunTable.Filter base, Set<String> ends, String after) {
        return new RunTable.Filter(base.from(), base.to(), ends, base.toolErrors(), base.tool(), base.model(), base.session(), base.sort(),
                after, base.limit());
    }

    private static List<String> prompts(RunTable.Page page) {
        return page.runs().stream().map(item -> item.row().prompt()).toList();
    }
}
