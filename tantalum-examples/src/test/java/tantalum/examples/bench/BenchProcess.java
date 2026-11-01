package tantalum.examples.bench;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import tantalum.shell.boot.TantalumApplication;

/** 一个样本：新进程启动服务，加载 {@link Corpus} 的数据，每项只测一次（首次调用，没有预热），结果逐行打印 {@code BENCH 名称 数值}。 */
public final class BenchProcess {

    private BenchProcess() {}

    public static void main(String[] args) throws Exception {
        Path data = Path.of(args[0]);
        int port = Integer.parseInt(args[1]);
        Corpus.Manifest manifest = Corpus.readManifest(data);
        ConfigurableApplicationContext context = new SpringApplicationBuilder(TantalumApplication.class).run(
                "--server.port=" + port,
                "--tantalum.data-dir=" + data,
                "--tantalum.workspace=" + Files.createTempDirectory("tantalum-bench-"),
                "--tantalum.provider=openai",
                "--tantalum.openai.model=bench",
                "--tantalum.openai.base-url=http://127.0.0.1:9/v1",
                "--logging.level.root=WARN");
        report("startup_ms", ManagementFactory.getRuntimeMXBean().getUptime());
        System.gc();
        report("retained_heap_mb", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() / (1 << 20));

        HttpClient http = HttpClient.newHttpClient();
        String base = "http://127.0.0.1:" + port;
        String longCookie = login(http, base, Corpus.LONG_USER);
        timed(http, base, longCookie, "sessions_ms", "/api/sessions");
        timed(http, base, longCookie, "open_long_session_ms", "/api/sessions/" + manifest.longSession() + "/messages");
        timed(http, base, longCookie, "turns_ms", "/api/runs/" + manifest.longRun() + "/turns");
        timed(http, base, longCookie, "trace_ms", "/api/runs/" + manifest.longRun() + "/trace");
        String quietCookie = login(http, base, Corpus.QUIET_USER);
        timed(http, base, quietCookie, "quiet_turns_ms", "/api/runs/" + manifest.quietRun() + "/turns");
        timed(http, base, quietCookie, "quiet_trace_ms", "/api/runs/" + manifest.quietRun() + "/trace");

        long peak = ManagementFactory.getMemoryPoolMXBeans().stream().filter(pool -> pool.getType() == MemoryType.HEAP)
                .map(MemoryPoolMXBean::getPeakUsage).mapToLong(usage -> usage.getUsed()).sum();
        report("peak_heap_mb", peak / (1 << 20));
        context.close();
        System.exit(0);
    }

    private static String login(HttpClient http, String base, String user) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/api/auth/login")).header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"" + user + "\",\"password\":\"" + Corpus.PASSWORD + "\"}")).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("login " + user + ": " + response.statusCode());
        }
        return response.headers().firstValue("set-cookie").orElseThrow().split(";")[0];
    }

    /** 非 200 或响应是空壳时直接失败，否则测到的可能只是一次 404。 */
    private static void timed(HttpClient http, String base, String cookie, String name, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + path)).header("Cookie", cookie).GET().build();
        long start = System.nanoTime();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        long elapsed = (System.nanoTime() - start) / 1_000_000;
        if (response.statusCode() != 200 || response.body().length() < 20) {
            throw new IllegalStateException(path + ": " + response.statusCode() + " " + response.body());
        }
        report(name, elapsed);
    }

    private static void report(String name, long value) {
        System.out.println("BENCH " + name + " " + value);
    }
}
