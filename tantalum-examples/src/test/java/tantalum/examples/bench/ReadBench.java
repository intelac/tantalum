package tantalum.examples.bench;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 读路径基准：每个规模先生成数据（已有则复用），每个样本一个新进程。
 * {@code mvn -P bench -pl tantalum-examples -am -DskipTests test-compile -Dbench.sizes=10000,100000,500000 -Dbench.samples=3}
 */
public final class ReadBench {

    private static final long SEED = 42;
    private static final int SAMPLE_TIMEOUT_MINUTES = 30;

    private ReadBench() {}

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        int samples = Integer.parseInt(args[2]);
        Budgets budgets = Budgets.fromSystemProperty();
        Long retainedAtSmallest = null;
        System.out.printf("machine: %s %s, %d cores, java %s, -Xmx4g%n", System.getProperty("os.name"), System.getProperty("os.arch"),
                Runtime.getRuntime().availableProcessors(), System.getProperty("java.version"));
        for (String size : args[1].split(",")) {
            int events = Integer.parseInt(size.trim());
            Path data = root.resolve("corpus-" + events);
            if (!Files.exists(data.resolve(Corpus.MANIFEST))) {
                long start = System.nanoTime();
                Corpus.generate(data, events, SEED);
                System.out.printf("generated %d in %d s%n", events, TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start));
            }
            Corpus.Manifest manifest = Corpus.readManifest(data);
            System.out.printf("%n== %d events, %d sessions, journal %d MB%n", manifest.events(), manifest.sessions(),
                    directorySize(data.resolve("sessions")) / (1 << 20));
            if (!Files.exists(data.resolve("index.db"))) {
                sample(data, root.resolve("warmup-" + events + ".log"), "warmup")
                        .forEach((name, value) -> System.out.printf("%-22s %8d   (one-off: the corpus has no index yet)%n", name, value));
            }
            Map<String, List<Long>> results = new LinkedHashMap<>();
            for (int i = 0; i < samples; i++) {
                sample(data, root.resolve("sample-" + events + "-" + i + ".log"))
                        .forEach((name, value) -> results.computeIfAbsent(name, key -> new ArrayList<>()).add(value));
            }
            results.forEach((name, values) -> System.out.printf("%-22s median %8d   samples %s%n", name, median(values), values));
            long retained = median(results.get("retained_heap_mb"));
            if (retainedAtSmallest == null) {
                retainedAtSmallest = retained;
            }
            if (events == Budgets.AT_EVENTS) {
                budgets.check("startup_ms", median(results.get("startup_ms")), Budgets.STARTUP_MS);
                budgets.check("retained_heap_mb", retained, Budgets.RETAINED_HEAP_MB);
                budgets.check("retained_growth_mb", retained - retainedAtSmallest, Budgets.RETAINED_GROWTH_MB);
                budgets.check("quiet_turns_ms", median(results.get("quiet_turns_ms")), Budgets.QUIET_QUERY_MS);
                budgets.check("quiet_trace_ms", median(results.get("quiet_trace_ms")), Budgets.QUIET_QUERY_MS);
                budgets.check("open_cold_ms", median(results.get("open_cold_ms")), Budgets.OPEN_COLD_MS);
                budgets.check("trace_page_ms", median(results.get("trace_page_ms")), Budgets.TRACE_PAGE_MS);
                budgets.check("open_resident_ms", median(results.get("open_resident_ms")), Budgets.OPEN_RESIDENT_MS);
            }
        }
        budgets.exitIfOver();
    }

    private static Map<String, Long> sample(Path data, Path log) throws IOException, InterruptedException {
        return sample(data, log, "sample");
    }

    private static Map<String, Long> sample(Path data, Path log, String mode) throws IOException, InterruptedException {
        Process child = new ProcessBuilder("java", "-Xmx4g", "--enable-native-access=ALL-UNNAMED",
                "-cp", System.getProperty("java.class.path"), BenchProcess.class.getName(), data.toString(), Integer.toString(freePort()), mode)
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!child.waitFor(SAMPLE_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            child.destroyForcibly();
            throw new IllegalStateException("sample timed out, see " + log);
        }
        if (child.exitValue() != 0) {
            throw new IllegalStateException("sample failed with " + child.exitValue() + ", see " + log);
        }
        Map<String, Long> values = new LinkedHashMap<>();
        for (String line : Files.readAllLines(log)) {
            if (line.startsWith("BENCH ")) {
                String[] parts = line.split(" ");
                values.put(parts[1], Long.parseLong(parts[2]));
            }
        }
        return values;
    }

    private static long median(List<Long> values) {
        return values.stream().sorted().toList().get(values.size() / 2);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static long directorySize(Path dir) throws java.io.IOException {
        try (var files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile).mapToLong(file -> file.toFile().length()).sum();
        }
    }
}
