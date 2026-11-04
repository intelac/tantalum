package tantalum.shell.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.journal.Line;
import tantalum.shell.journal.SessionFileReader;
import tantalum.shell.loop.SessionSlice;
import tantalum.value.decision.StepRecord;
import tantalum.value.state.RunState;

/**
 * record-results 任务 9（需求 5、11.3）：结果行落盘前崩溃留下的尾部，启动时交给内核处理一次，补写的结果和当时会写的相同；
 * 补到一半再启动只补剩下的。
 */
class BackfillTest {

    @TempDir
    Path dir;

    private ReplayScenario scenario;
    private SessionFileReader reader;
    private RecordReplay records;
    private Path file;

    @BeforeEach
    void start() throws Exception {
        scenario = new ReplayScenario(dir);
        scenario.run();
        scenario.writer.flush();
        reader = new SessionFileReader(EventCodec.withBuiltins());
        records = new RecordReplay(scenario.paths, reader);
        file = scenario.paths.sessionFile(ReplayScenario.ALICE, ReplayScenario.TOOLS);
    }

    @AfterEach
    void stop() throws Exception {
        scenario.close();
    }

    private List<StepRecord> results() throws Exception {
        List<StepRecord> found = new ArrayList<>();
        reader.forEachLine(file, line -> {
            if (line instanceof Line.Result result) {
                found.add(result.record());
            }
        });
        return found;
    }

    /** 去掉最后 {@code count} 行结果行，就像它们没来得及落盘。 */
    private void dropLastResults(int count) throws Exception {
        List<String> lines = new ArrayList<>(Files.readAllLines(file));
        for (int dropped = 0, i = lines.size() - 1; dropped < count; i--) {
            if (lines.get(i).startsWith("{\"result\"")) {
                lines.remove(i);
                dropped++;
            }
        }
        Files.write(file, lines);
    }

    private int backfill() throws Exception {
        return Backfill.run(scenario.index, scenario.paths, records, scenario.fixture.files, scenario.kernel);
    }

    private static List<RunState> sorted(List<RunState> runs) {
        return runs.stream().sorted(Comparator.comparing(run -> run.ref().run().value())).toList();
    }

    @Test
    void theTailIsHandledOnceAndGivesTheResultsItWouldHaveHad() throws Exception {
        List<StepRecord> original = results();
        dropLastResults(3);

        assertEquals(3, backfill());

        assertEquals(original, results(), "the kernel decides the same as it did before the crash");
        SessionReplay replay = new SessionReplay(scenario.paths, reader, new UsageLedger(scenario.index, scenario.writer), scenario.kernel);
        SessionSlice kernel = replay.load(ReplayScenario.ALICE, ReplayScenario.TOOLS, Long.MAX_VALUE).orElseThrow();
        SessionSlice folded = records.load(ReplayScenario.ALICE, ReplayScenario.TOOLS, Long.MAX_VALUE).orElseThrow().slice();
        assertEquals(kernel.session(), folded.session());
        assertEquals(sorted(kernel.runs()), sorted(folded.runs()));
        assertEquals(kernel.transcript(), folded.transcript());

        assertEquals(0, backfill(), "nothing left the second time");
    }

    @Test
    void aBackfillCutShortIsFinishedNextTime() throws Exception {
        List<StepRecord> original = results();
        dropLastResults(3);
        assertEquals(3, backfill());
        // 第二次崩溃发生在补写到一半：最后两行又没了
        dropLastResults(2);

        assertEquals(2, backfill());

        assertEquals(original, results());
    }

    @Test
    void filesInStepWithTheIndexAreNotRead() throws Exception {
        assertEquals(0, backfill());
        assertTrue(results().size() > 5);
    }
}
