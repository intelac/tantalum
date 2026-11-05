package tantalum.shell.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.kernel.Kernel;
import tantalum.kernel.Limits;
import tantalum.kernel.hook.HookChain;
import tantalum.kernel.step.BuiltinSteps;
import tantalum.kernel.table.TransitionTable;

/** record-results 任务 12（需求 10）：检查工具对一致的目录退出 0，对和结果行不符的会话退出 1 并写明第一处差异，不改任何文件。 */
class RecordCheckTest {

    @TempDir
    Path dir;

    private ReplayScenario scenario;

    @BeforeEach
    void start() throws Exception {
        scenario = new ReplayScenario(dir);
        scenario.run();
        scenario.writer.flush();
    }

    @AfterEach
    void stop() throws Exception {
        scenario.close();
    }

    private Map<Path, String> sessionFiles() throws Exception {
        Map<Path, String> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(dir.resolve("sessions"))) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                files.put(file, Files.readString(file));
            }
        }
        return files;
    }

    private static String printed(RecordCheck.Report report) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RecordCheck.print(report, new PrintStream(out, true, StandardCharsets.UTF_8));
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void theSameKernelFindsNothingAndChangesNothing() throws Exception {
        Map<Path, String> before = sessionFiles();

        RecordCheck.Report report = RecordCheck.run(dir, scenario.kernel);

        assertEquals(List.of(), report.problems());
        assertEquals(0, report.exitCode());
        assertEquals(4, report.sessions());
        assertTrue(printed(report).contains("all 4 sessions"));
        assertEquals(before, sessionFiles());
    }

    /** 额度钩子要的是全部会话的累计用量：检查工具从结果行算，所以“额度用完就停”的会话照样一致。没有钩子的内核在那一步决定不同。 */
    @Test
    void aKernelThatDecidesDifferentlyIsReportedAtTheFirstStepThatDiffers() throws Exception {
        TransitionTable table = new TransitionTable();
        BuiltinSteps.register(table);
        Kernel withoutQuota = new Kernel(table, new HookChain(List.of()), (tool, args) -> tool.startsWith("safe"), Limits.DEFAULT);

        RecordCheck.Report report = RecordCheck.run(dir, withoutQuota);

        assertEquals(1, report.exitCode());
        // 额度在 alice 的后几次提问时已经用完：那几个会话不同，bob 的和 alice 第一个会话不受影响
        assertTrue(report.problems().stream().anyMatch(problem -> problem.startsWith("alice/stopped: seq ")), report.problems().toString());
        assertTrue(report.problems().stream().allMatch(problem -> problem.startsWith("alice/") && !problem.startsWith("alice/compacted")),
                report.problems().toString());
        assertTrue(printed(report).contains("DIFFERENT alice/stopped"));
    }

    @Test
    void aResultLineEditedByHandIsReported() throws Exception {
        Path file = scenario.paths.sessionFile(ReplayScenario.BOB, ReplayScenario.BOBS);
        String content = Files.readString(file);
        Files.writeString(file, content.replaceFirst("(\"result\":\\{[^\\n]*?)\"bob again\"", "$1\"bob, later\""));

        RecordCheck.Report report = RecordCheck.run(dir, scenario.kernel);

        assertEquals(List.of("bob/bobs: seq " + firstSeqOf("bob again") + " session"), report.problems());
    }

    private long firstSeqOf(String text) {
        return scenario.fixture.events.stream().filter(event -> event.event().toString().contains(text))
                .findFirst().orElseThrow().seq();
    }
}
