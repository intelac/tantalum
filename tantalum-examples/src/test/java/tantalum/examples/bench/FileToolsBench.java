package tantalum.examples.bench;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.ToolOutput;
import tantalum.shell.plugin.Workspace;
import tantalum.shell.tool.FindFilesTool;
import tantalum.shell.tool.ReadFileTool;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;

/**
 * 文件工具预算（core-tools 非功能“性能”）：64 MB、约 70 万行的文件，从中间读一个 100 行的窗口（要数完全部行数才能写出“共多少行”）；
 * 一万个文件、几百个目录的树里按名字找文件。
 * {@code mvn -P bench -pl tantalum-examples -am -DskipTests test-compile -Dbench.main=tantalum.examples.bench.FileToolsBench}
 */
public final class FileToolsBench {

    private static final long FILE_BYTES = 64L * 1024 * 1024 - 1024;

    private FileToolsBench() {}

    public static void main(String[] args) throws Exception {
        Path dir = Files.createDirectories(Path.of(args[0])).resolve("files-" + System.nanoTime());
        int samples = args.length > 2 ? Integer.parseInt(args[2]) : 5;
        Path root = Files.createDirectories(dir.resolve("workspace"));
        int lines = bigFile(root.resolve("big.log"));
        tree(root.resolve("tree"));
        ToolContext context = new ToolContext(new Principal("bench"), new SessionId("bench"), new Workspace(root));
        System.out.printf("big.log %.1f MB, %d lines%n", Files.size(root.resolve("big.log")) / 1048576.0, lines);

        ReadFileTool read = new ReadFileTool();
        Json window = new Json.Obj(Map.of("path", new Json.Str("big.log"), "offset", new Json.Num(java.math.BigDecimal.valueOf(lines / 2)),
                "limit", new Json.Num(java.math.BigDecimal.valueOf(100))));
        long readMs = median(samples, () -> read.run(context, window));
        FindFilesTool find = new FindFilesTool();
        Json pattern = new Json.Obj(Map.of("pattern", new Json.Str("*.java"), "path", new Json.Str("tree")));
        long findMs = median(samples, () -> find.run(context, pattern));

        Budgets budgets = Budgets.fromSystemProperty();
        budgets.check("read_window_64mb_ms", readMs, Budgets.READ_WINDOW_64MB_MS);
        budgets.check("find_10k_files_ms", findMs, Budgets.FIND_10K_FILES_MS);
        budgets.exitIfOver();
    }

    /** 第 0 次是 JIT 预热，不计入。 */
    private static long median(int samples, Supplier<ToolOutput> call) {
        List<Long> times = new ArrayList<>();
        for (int i = 0; i <= samples; i++) {
            long started = System.nanoTime();
            ToolOutput output = call.get();
            long ms = (System.nanoTime() - started) / 1_000_000;
            if (output.error()) {
                throw new IllegalStateException(output.text());
            }
            String text = output.text();
            System.out.printf("sample %d: %d ms, %s%n", i, ms, text.substring(Math.max(0, text.lastIndexOf('\n') + 1)));
            if (i > 0) {
                times.add(ms);
            }
        }
        Collections.sort(times);
        return times.get(times.size() / 2);
    }

    /** 像日志的行，长短不一，夹着中文。 */
    private static int bigFile(Path file) throws Exception {
        int count = 0;
        long written = 0;
        try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            while (written < FILE_BYTES - 200) {
                String line = "2026-11-03T14:" + (count % 60) + ":00Z INFO request " + count + " 用户 bench " + "x".repeat(count % 80);
                out.write(line);
                out.write('\n');
                written += line.getBytes(StandardCharsets.UTF_8).length + 1;
                count++;
            }
        }
        return count;
    }

    /** 一万个文件分在 400 个目录里，三层深，四分之一是 .java。 */
    private static void tree(Path root) throws Exception {
        for (int i = 0; i < 10_000; i++) {
            Path dir = root.resolve("m" + i % 20).resolve("p" + i % 400 / 20).resolve("s" + i % 5);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("f" + i + (i % 4 == 0 ? ".java" : ".txt")), "x");
        }
    }
}
