package tantalum.shell.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.journal.SessionCorruptException;
import tantalum.shell.journal.SessionFileReader;
import tantalum.shell.loop.SessionSlice;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;
import tantalum.value.state.RunState;

/** record-results 任务 8：用结果行折叠和用内核重放得到同样的会话、运行、对话和轨迹，每一步的决定也相同。 */
class RecordReplayTest {

    private static final Map<SessionId, Principal> SESSIONS = Map.of(
            ReplayScenario.TOOLS, ReplayScenario.ALICE, ReplayScenario.COMPACTED, ReplayScenario.ALICE,
            ReplayScenario.STOPPED, ReplayScenario.ALICE, ReplayScenario.BOBS, ReplayScenario.BOB);

    @TempDir
    Path dir;

    private ReplayScenario scenario;
    private SessionReplay replay;
    private RecordReplay records;

    @BeforeEach
    void start() throws Exception {
        scenario = new ReplayScenario(dir);
        SessionFileReader reader = new SessionFileReader(EventCodec.withBuiltins());
        replay = new SessionReplay(scenario.paths, reader, new UsageLedger(scenario.index, scenario.writer), scenario.kernel);
        records = new RecordReplay(scenario.paths, reader);
        scenario.run();
        scenario.writer.flush();
    }

    @AfterEach
    void stop() throws Exception {
        scenario.close();
    }

    private static List<RunState> sorted(List<RunState> runs) {
        return runs.stream().sorted(Comparator.comparing(run -> run.ref().run().value())).toList();
    }

    @Test
    void foldingTheResultsGivesTheSameSliceAsReplayingTheKernel() throws Exception {
        for (var entry : SESSIONS.entrySet()) {
            SessionSlice kernel = replay.load(entry.getValue(), entry.getKey(), Long.MAX_VALUE).orElseThrow();
            SessionSlice folded = records.load(entry.getValue(), entry.getKey(), Long.MAX_VALUE).orElseThrow().slice();

            assertEquals(kernel.session(), folded.session(), entry.getKey().value());
            assertEquals(sorted(kernel.runs()), sorted(folded.runs()), entry.getKey().value());
            assertEquals(kernel.transcript(), folded.transcript(), entry.getKey().value());
            assertEquals(kernel.trace(), folded.trace(), entry.getKey().value());
            assertEquals(kernel.seq(), folded.seq());
        }
    }

    @Test
    void everyStepHasTheSameDecisionAsTheKernelHad() throws Exception {
        for (var entry : SESSIONS.entrySet()) {
            SessionId session = entry.getKey();
            List<IndexFixture.Stepped> live = scenario.fixture.steps.stream()
                    .filter(step -> step.envelope().session().equals(session)).toList();
            List<String> seen = new ArrayList<>();
            records.each(entry.getValue(), scenario.paths.sessionFile(entry.getValue(), session), (envelope, before, decision) -> {
                IndexFixture.Stepped expected = live.get(seen.size());
                seen.add(envelope.toString());
                assertEquals(expected.envelope(), envelope);
                assertEquals(expected.decision().commands(), decision.commands());
                assertEquals(expected.decision().facts(), decision.facts());
                assertEquals(expected.decision().newState().sessions().get(session), decision.newState().sessions().get(session));
            });
            assertEquals(live.size(), seen.size(), session.value());
        }
    }

    @Test
    void stopsBeforeTheGivenSeq() throws Exception {
        List<Long> seqs = scenario.fixture.events.stream()
                .filter(event -> event.session().equals(ReplayScenario.TOOLS)).map(event -> event.seq()).toList();
        SessionSlice slice = records.load(ReplayScenario.ALICE, ReplayScenario.TOOLS, seqs.get(4)).orElseThrow().slice();
        assertEquals(seqs.get(3), slice.seq());
    }

    @Test
    void anEventWithoutAResultIsSkippedByQueriesButRefusedByLoading() throws Exception {
        Path file = scenario.paths.sessionFile(ReplayScenario.ALICE, ReplayScenario.TOOLS);
        List<String> lines = new ArrayList<>(Files.readAllLines(file));
        String last = lines.removeLast();
        assertTrue(last.startsWith("{\"result\""), last);
        Files.write(file, lines);

        List<Long> seen = new ArrayList<>();
        records.each(ReplayScenario.ALICE, file, (envelope, before, decision) -> seen.add(envelope.seq()));
        assertEquals(scenario.fixture.events.stream().filter(event -> event.session().equals(ReplayScenario.TOOLS)).count() - 1,
                seen.size(), "the step still in flight is not shown yet");

        assertThrows(SessionCorruptException.class, () -> records.load(ReplayScenario.ALICE, ReplayScenario.TOOLS, Long.MAX_VALUE));
    }

    @Test
    void aResultThatSkipsAnEventIsCorrupt() throws Exception {
        Path file = scenario.paths.sessionFile(ReplayScenario.ALICE, ReplayScenario.TOOLS);
        List<String> lines = new ArrayList<>(Files.readAllLines(file));
        // 第一个事件的结果行挪到第二个事件的结果行之后：第二个结果回答的不是最早的那个事件
        int first = indexOfResult(lines, 0);
        int second = indexOfResult(lines, first + 1);
        lines.add(second, lines.remove(first));
        Files.write(file, lines);

        assertThrows(SessionCorruptException.class,
                () -> records.each(ReplayScenario.ALICE, file, (envelope, before, decision) -> { }));
    }

    private static int indexOfResult(List<String> lines, int from) {
        for (int i = from; i < lines.size(); i++) {
            if (lines.get(i).startsWith("{\"result\"")) {
                return i;
            }
        }
        throw new AssertionError("no result line after " + from);
    }
}
