package tantalum.shell.store;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tantalum.kernel.Kernel;
import tantalum.shell.cli.CliText.Key;
import tantalum.shell.cli.CliText;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.journal.Line;
import tantalum.shell.journal.SessionFileReader;
import tantalum.shell.journal.SessionPaths;
import tantalum.value.event.EventEnvelope;
import tantalum.value.state.PrincipalState;
import tantalum.value.text.Language;

/**
 * 回归检查（record-results R12）：用当前的内核把数据目录里的每个会话重放一遍，和结果行逐步比较，
 * 列出每个不一致会话的第一处差异。部署前用新代码跑一遍，就知道它会不会改变旧会话的行为。
 * 只读：不取存储锁、不打开索引库、不写任何文件。钩子要的累计用量从结果行里算。
 */
public final class RecordCheck {

    /** 检查结果：一致的会话数，以及不一致或读不了的会话各一行说明。 */
    public record Report(int sessions, List<String> problems) {

        public int exitCode() {
            return problems.isEmpty() ? 0 : 1;
        }
    }

    private RecordCheck() {}

    public static Report run(Path dataDir, Kernel kernel) throws IOException {
        SessionPaths paths = new SessionPaths(dataDir);
        SessionFileReader reader = new SessionFileReader(EventCodec.withBuiltins());
        RecordReplay records = new RecordReplay(paths, reader);
        int sessions = 0;
        List<String> problems = new ArrayList<>();
        for (Path dir : paths.users()) {
            List<Path> files = paths.sessionFiles(dir);
            List<IndexChange.Usage> usage = new ArrayList<>();
            for (Path file : files) {
                try {
                    reader.forEachLine(file, line -> {
                        if (line.record().usage() != null) {
                            usage.add(new IndexChange.Usage(line.seq(), line.record().usageDay(), line.record().usage()));
                        }
                    });
                } catch (RuntimeException | IOException unreadable) {
                    // 下面逐个文件检查时会报出来
                }
            }
            usage.sort(Comparator.comparingLong(IndexChange.Usage::seq));
            SessionReplay replay = new SessionReplay(paths, reader,
                    (principal, seq) -> new UsageLedger.Cursor(PrincipalState.start(principal), usage), kernel);
            for (Path file : files) {
                sessions++;
                try {
                    List<EventEnvelope> events = reader.readAll(file);
                    if (events.isEmpty()) {
                        continue;
                    }
                    EventEnvelope first = events.getFirst();
                    Optional<ReplayCompare.Difference> difference = ReplayCompare.firstDifference(first.principal(), first.session(),
                            file, Long.MAX_VALUE, replay, records);
                    difference.ifPresent(found -> problems.add(first.principal().value() + "/" + first.session().value()
                            + ": seq " + found.seq() + " " + found.field()));
                } catch (Exception failure) {
                    problems.add(file + ": cannot be read: " + failure.getMessage());
                }
            }
        }
        return new Report(sessions, List.copyOf(problems));
    }

    /** 打印结果（英文），返回退出码：全部一致为 0，否则为 1。 */
    public static int print(Report report, PrintStream out) {
        return print(report, out, CliText.of(Language.EN));
    }

    /** 按 {@code text} 的语言打印结果（console-i18n I13）；每个不一致的说明（会话、seq、字段）是给人查的固定写法，不翻译。 */
    public static int print(Report report, PrintStream out, CliText text) {
        for (String problem : report.problems()) {
            out.println(text.say(Key.CHECK_DIFFERENT, Map.of("problem", problem)));
        }
        out.println(report.problems().isEmpty()
                ? text.say(Key.CHECK_ALL_OK, Map.of("sessions", report.sessions()))
                : text.say(Key.CHECK_SOME_DIFFER, Map.of("count", report.problems().size(), "sessions", report.sessions())));
        return report.exitCode();
    }
}
