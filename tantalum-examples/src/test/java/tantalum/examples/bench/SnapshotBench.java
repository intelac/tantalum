package tantalum.examples.bench;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import tantalum.kernel.Kernel;
import tantalum.kernel.hook.HookChain;
import tantalum.kernel.table.BuiltinSteps;
import tantalum.kernel.table.TransitionTable;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.journal.SessionFileReader;
import tantalum.shell.journal.SessionPaths;
import tantalum.shell.store.IndexWriter;
import tantalum.shell.store.SessionIndex;
import tantalum.shell.store.SessionReplay;
import tantalum.shell.store.SnapshotStore;
import tantalum.shell.store.UsageLedger;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;

/**
 * 任务 49：长会话冷载入，有无快照各测一次，同时量写快照的耗时和文件大小。两种情况交替、各用新进程，
 * 都是第一次调用。用 {@link ReadBench} 生成的数据（要先跑过它，索引库已建好）。
 * {@code mvn -P bench -pl tantalum-examples -am -DskipTests test-compile -Dbench.main=tantalum.examples.bench.SnapshotBench -Dbench.sizes=500000}
 */
public final class SnapshotBench {

    private SnapshotBench() {}

    public static void main(String[] args) throws Exception {
        if (args.length > 3) {
            sample(Path.of(args[0]), args[3]);
            return;
        }
        Path root = Path.of(args[0]);
        int samples = Integer.parseInt(args[2]);
        for (String size : args[1].split(",")) {
            Path data = root.resolve("corpus-" + size.trim());
            if (!Files.exists(data.resolve("index.db"))) {
                throw new IllegalStateException("run ReadBench for " + size + " first: " + data);
            }
            Map<String, List<Long>> results = new LinkedHashMap<>();
            for (int i = 0; i < samples; i++) {
                for (String mode : List.of("without", "with")) {
                    Process child = new ProcessBuilder("java", "-Xmx4g", "--enable-native-access=ALL-UNNAMED", "-cp", System.getProperty("java.class.path"),
                            SnapshotBench.class.getName(), data.toString(), "-", "-", mode).redirectErrorStream(true).start();
                    String output = new String(child.getInputStream().readAllBytes());
                    if (!child.waitFor(10, TimeUnit.MINUTES) || child.exitValue() != 0) {
                        throw new IllegalStateException(mode + " sample failed:\n" + output);
                    }
                    for (String line : output.lines().toList()) {
                        if (line.startsWith("BENCH ")) {
                            String[] parts = line.split(" ");
                            results.computeIfAbsent(parts[1], key -> new ArrayList<>()).add(Long.parseLong(parts[2]));
                        }
                    }
                }
            }
            System.out.printf("%n== %s events, long session%n", size);
            results.forEach((name, values) -> System.out.printf("%-26s median %8d   samples %s%n", name,
                    values.stream().sorted().toList().get(values.size() / 2), values));
        }
    }

    /** {@code without}：删掉快照后载入，再写一份快照；{@code with}：用上一步写的快照载入。 */
    private static void sample(Path data, String mode) throws Exception {
        Corpus.Manifest manifest = Corpus.readManifest(data);
        Principal principal = new Principal(Corpus.LONG_USER);
        SessionId session = new SessionId(manifest.longSession());
        SessionPaths paths = new SessionPaths(data);
        EventCodec codec = EventCodec.withBuiltins();
        TransitionTable table = new TransitionTable();
        BuiltinSteps.register(table);
        Kernel kernel = new Kernel(table, new HookChain(List.of()), (tool, arguments) -> true, 10);
        try (SessionIndex index = SessionIndex.open(data); IndexWriter writer = new IndexWriter(index, 200, Duration.ofSeconds(5))) {
            SnapshotStore snapshots = new SnapshotStore(paths, codec);
            SessionReplay replay = new SessionReplay(paths, new SessionFileReader(codec), snapshots, new UsageLedger(index, writer), kernel);
            if (mode.equals("without")) {
                Files.deleteIfExists(paths.snapshotFile(principal, session));
            }
            long start = System.nanoTime();
            SessionReplay.Result slice = replay.load(principal, session, Long.MAX_VALUE).orElseThrow();
            report("load_" + mode + "_snapshot_ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            report("replayed_events_" + mode, slice.replayedEvents());
            if (mode.equals("without")) {
                report("transcript_items", slice.snapshot().slice().transcript().items().size());
                long write = System.nanoTime();
                snapshots.write(principal, slice.snapshot());
                report("write_snapshot_ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - write));
                report("snapshot_kb", Files.size(paths.snapshotFile(principal, session)) / 1024);
                report("session_file_kb", Files.size(paths.sessionFile(principal, session)) / 1024);
            }
        }
    }

    private static void report(String name, long value) {
        System.out.println("BENCH " + name + " " + value);
    }
}
