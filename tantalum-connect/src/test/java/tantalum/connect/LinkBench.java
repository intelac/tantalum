package tantalum.connect;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tantalum.local.fs.AllowedDirs;
import tantalum.local.tool.LocalContext;
import tantalum.local.tool.LocalResult;
import tantalum.local.tool.Observations;
import tantalum.local.tool.ReadFile;
import tantalum.shell.boot.Engine;
import tantalum.shell.local.LocalLinks;
import tantalum.shell.model.ModelOutcome;
import tantalum.shell.web.TestServer;
import tantalum.value.id.Principal;
import tantalum.value.json.Json;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;

/**
 * local-workspace 的性能要求（NFR Performance，任务 24）：
 * <ul>
 *   <li>同一台电脑上 {@code read_file} 经过连接程序的往返，比直接执行多花的时间 P95 ≤ 20 ms：各 1000 次（先各热身 200 次）；</li>
 *   <li>连接程序空闲时内存 ≤ 150 MB：调用完等 10 秒，量进程的 RSS（{@code ps}），另打印 {@code jcmd GC.heap_info} 作参考。</li>
 * </ul>
 * 连接程序是单独的进程，和用户实际跑的一样：有安装包（{@code -Pmac} 打过）时用包里的 {@code tantalum-connect}，没有时用
 * {@code java -cp … tantalum.connect.Main}。令牌只放在子进程的环境变量里。不是常规测试，按需跑：
 * <pre>mvn -o -pl tantalum-connect -am verify -Djacoco.skip=true -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false \
 *     -Dit.test=LinkBench -Dfailsafe.failIfNoSpecifiedTests=false</pre>
 */
class LinkBench {

    static final int WARMUP = 200;
    static final int CALLS = 1000;
    static final long MAX_OVERHEAD_MS = 20;
    static final long MAX_IDLE_MB = 150;
    static final Path APP = Path.of("target/package/app/Tantalum Connect.app/Contents/MacOS/tantalum-connect");

    @TempDir
    Path temp;

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void readFileRoundTripAndIdleMemory() throws Exception {
        Path work = Files.createDirectory(temp.toRealPath().resolve("work"));
        StringBuilder source = new StringBuilder();
        for (int i = 0; i < 120; i++) {
            source.append("    int field").append(i).append(" = ").append(i).append("; // a line of ordinary Java source\n");
        }
        Files.writeString(work.resolve("Order.java"), source);
        Json arguments = new Json.Obj(Map.of("path", new Json.Str("Order.java")));

        try (TestServer server = new TestServer(temp.resolve("data"), temp.resolve("ws"),
                (prompt, chunks) -> new ModelOutcome.Replied(List.of(new TextBlock("ok")), StopReason.END_TURN, "m", null))) {
            LocalLinks links = server.context.getBean(Engine.class).localLinks();
            server.users().add("bench", TestServer.PASSWORD);
            Principal user = new Principal("bench");
            String token = links.tokens().issue(user, Duration.ofHours(1)).token();
            boolean packaged = Files.isExecutable(APP);
            List<String> command = new ArrayList<>(packaged ? List.of(APP.toString())
                    : List.of(ProcessHandle.current().info().command().orElse("java"), "-cp", classpath(), "tantalum.connect.Main"));
            command.addAll(List.of("--server", "http://127.0.0.1:" + server.port(), "--allow", work.toString(), "--name", "Bench Mac"));
            if (packaged) {
                command.add("--allow-shell");
            }
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
            builder.environment().put("TANTALUM_TOKEN", token);
            Process connector = builder.start();
            StringBuffer terminal = new StringBuffer();
            Thread.ofVirtual().start(() -> copy(connector.getInputStream(), terminal));
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                while (links.status(user).isEmpty()) {
                    assertTrue(System.nanoTime() < deadline, "the connector never connected:\n" + terminal);
                    Thread.sleep(20);
                }

                ReadFile readFile = new ReadFile();
                LocalContext direct = new LocalContext("bench", new AllowedDirs(List.of(work)), new Observations());
                long[] local = time(() -> readFile.run(direct, arguments));
                long[] linked = time(() -> links.call(user, "bench", "read_file", arguments, Duration.ofSeconds(10)));
                report("直接执行 read_file", local);
                report("经过连接程序 read_file", linked);
                long overhead = percentile(linked, 95) - percentile(local, 95);
                System.out.printf("P95 多花：%.2f ms（上限 %d ms）%n", overhead / 1e6, MAX_OVERHEAD_MS);

                if (links.status(user).orElseThrow().hello().shell().available()) {
                    Json bash = new Json.Obj(Map.of("command", new Json.Str("true")));
                    long[] shell = new long[20];
                    for (int i = 0; i < shell.length; i++) {
                        long started = System.nanoTime();
                        LocalResult result = links.call(user, "bench", "bash", bash, Duration.ofSeconds(30));
                        shell[i] = System.nanoTime() - started;
                        assertFalse(result.error(), result.text());
                    }
                    Arrays.sort(shell);
                    report("经过连接程序 bash true（参考）", shell);
                }

                Thread.sleep(10_000);
                long rssKb = Long.parseLong(run("ps", "-o", "rss=", "-p", Long.toString(connector.pid())).strip());
                System.out.printf("连接程序（%s）空闲 RSS：%d MB（上限 %d MB）%n", packaged ? "安装包" : "java -cp", rssKb / 1024, MAX_IDLE_MB);
                String jcmd = Path.of(System.getProperty("java.home"), "bin", "jcmd").toString();
                System.out.println(run(jcmd, Long.toString(connector.pid()), "GC.heap_info"));

                assertTrue(overhead <= TimeUnit.MILLISECONDS.toNanos(MAX_OVERHEAD_MS), "P95 overhead " + overhead / 1e6 + " ms");
                assertTrue(rssKb / 1024 <= MAX_IDLE_MB, "idle RSS " + rssKb / 1024 + " MB");
                assertFalse(terminal.toString().contains(token), "the token never reaches the terminal");
            } finally {
                connector.descendants().forEach(ProcessHandle::destroyForcibly);
                connector.destroyForcibly();
                connector.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    @FunctionalInterface
    private interface Call {
        LocalResult run() throws Exception;
    }

    /** 先热身，再计时；每次都必须成功。返回排好序的纳秒数。 */
    private static long[] time(Call call) throws Exception {
        for (int i = 0; i < WARMUP; i++) {
            assertFalse(call.run().error());
        }
        long[] times = new long[CALLS];
        for (int i = 0; i < CALLS; i++) {
            long started = System.nanoTime();
            LocalResult result = call.run();
            times[i] = System.nanoTime() - started;
            assertFalse(result.error(), result.text());
        }
        Arrays.sort(times);
        return times;
    }

    private static long percentile(long[] sorted, int percent) {
        return sorted[Math.min(sorted.length - 1, (int) Math.ceil(sorted.length * percent / 100.0) - 1)];
    }

    private static void report(String what, long[] sorted) {
        System.out.printf("%s：%d 次，P50 %.2f ms，P95 %.2f ms，P99 %.2f ms，最大 %.2f ms%n", what, sorted.length,
                percentile(sorted, 50) / 1e6, percentile(sorted, 95) / 1e6, percentile(sorted, 99) / 1e6, sorted[sorted.length - 1] / 1e6);
    }

    private static void copy(InputStream in, StringBuffer into) {
        byte[] buffer = new byte[4096];
        try (in) {
            for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                into.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
            }
        } catch (IOException closed) {
            // 进程结束
        }
    }

    private static String run(String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        process.waitFor();
        return output;
    }

    /** 没有安装包时：连接程序和它用到的几个库所在的位置。 */
    private static String classpath() {
        List<Class<?>> anchors = List.of(Main.class, tantalum.local.wire.Wire.class, Json.class,
                tools.jackson.databind.ObjectMapper.class, tools.jackson.core.JsonParser.class,
                com.fasterxml.jackson.annotation.JsonProperty.class);
        return anchors.stream().map(type -> {
            try {
                return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
            } catch (URISyntaxException impossible) {
                throw new IllegalStateException(impossible);
            }
        }).distinct().collect(Collectors.joining(File.pathSeparator));
    }
}
