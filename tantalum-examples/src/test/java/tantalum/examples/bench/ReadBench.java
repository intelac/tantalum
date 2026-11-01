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
                    Files.size(data.resolve("journal.db")) / (1 << 20));
            Map<String, List<Long>> results = new LinkedHashMap<>();
            for (int i = 0; i < samples; i++) {
                sample(data, root.resolve("sample-" + events + "-" + i + ".log"))
                        .forEach((name, value) -> results.computeIfAbsent(name, key -> new ArrayList<>()).add(value));
            }
            results.forEach((name, values) -> System.out.printf("%-22s median %8d   samples %s%n", name, median(values), values));
        }
    }

    private static Map<String, Long> sample(Path data, Path log) throws IOException, InterruptedException {
        Process child = new ProcessBuilder("java", "-Xmx4g", "--enable-native-access=ALL-UNNAMED",
                "-cp", System.getProperty("java.class.path"), BenchProcess.class.getName(), data.toString(), Integer.toString(freePort()))
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
}
