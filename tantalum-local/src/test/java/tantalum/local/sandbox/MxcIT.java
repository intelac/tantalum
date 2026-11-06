package tantalum.local.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.local.fs.AllowedDirs;
import tantalum.local.tool.LocalContext;
import tantalum.local.tool.LocalResult;
import tantalum.local.tool.LocalTool;
import tantalum.local.tool.Observations;
import tantalum.local.tool.Tools;
import tantalum.value.json.Json;

/**
 * 用真的 {@code mxc-exec-mac} 跑 {@code bash}（local-workspace 需求 4、8.2，L13、L14）。只在 macOS 上、程序存在时跑，否则跳过并写明原因。
 * 程序的位置：系统属性 {@code tantalum.mxc}，没有时用打包时下载、校验过的那份（{@code ~/.cache/tantalum/mxc/1.0.1/mxc-exec-mac}，
 * 见 {@code tantalum-connect/src/package/build-mac.sh}）。
 */
class MxcIT {

    static final Path CACHED = Path.of(System.getProperty("user.home"), ".cache/tantalum/mxc/1.0.1/mxc-exec-mac");

    private static Path program;

    @TempDir
    Path temp;

    private Path work;
    private Path outside;
    private final List<Sandbox> opened = new java.util.ArrayList<>();

    @BeforeAll
    static void findProgram() {
        assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("mac"), "MXC 的 macOS 沙箱只在 macOS 上测");
        String configured = System.getProperty("tantalum.mxc");
        program = configured == null || configured.isBlank() ? CACHED : Path.of(configured);
        assumeTrue(Files.isExecutable(program), "没有 " + program + "：先跑 mvn -o -pl tantalum-connect -am package -Pmac -DskipTests"
                + " 下载并校验 MXC，或用 -Dtantalum.mxc=路径 指定");
    }

    @BeforeEach
    void setUp() throws IOException {
        work = Files.createDirectory(temp.toRealPath().resolve("work"));
        outside = Files.createDirectory(temp.toRealPath().resolve("outside"));
        Files.writeString(work.resolve("a.txt"), "hello\n");
        Files.writeString(outside.resolve("secret.txt"), "OUTSIDE-SECRET\n");
    }

    @AfterEach
    void tearDown() {
        opened.forEach(Sandbox::close);
    }

    private Sandbox sandbox(boolean write, boolean network, List<String> env, Map<String, String> hostEnv) throws IOException {
        MxcPolicy policy = MxcPolicy.of(new AllowedDirs(List.of(work)), write, List.of(), network, env, hostEnv);
        Sandbox sandbox = Mxc.open(program, null, policy);
        opened.add(sandbox);
        Sandbox.Status status = sandbox.check();
        assertTrue(status.available(), "the check passes on this Mac: " + status.reason());
        return sandbox;
    }

    private Sandbox sandbox(boolean write) throws IOException {
        return sandbox(write, true, List.of(), Map.of());
    }

    private LocalResult bash(Sandbox sandbox, String command, Object... more) throws Exception {
        Map<String, Json> fields = new java.util.LinkedHashMap<>();
        fields.put("command", new Json.Str(command));
        for (int i = 0; i < more.length; i += 2) {
            fields.put((String) more[i], more[i + 1] instanceof Integer number ? new Json.Num(new java.math.BigDecimal(number))
                    : new Json.Str((String) more[i + 1]));
        }
        LocalTool bash = Tools.all().stream().filter(tool -> tool.name().equals("bash")).findFirst().orElseThrow();
        return bash.run(new LocalContext("s-1", new AllowedDirs(List.of(work)), new Observations(), sandbox), new Json.Obj(fields));
    }

    /** 读得到允许的目录；读不到允许目录外的文件、真实家目录里的文件和 ~/.ssh。 */
    @Test
    void theCommandCannotReadOutsideTheAllowedDirectories() throws Exception {
        Sandbox sandbox = sandbox(true);
        assertEquals("hello", bash(sandbox, "cat a.txt").text());

        LocalResult other = bash(sandbox, "cat " + outside.resolve("secret.txt"));
        assertTrue(other.error() && !other.text().contains("OUTSIDE-SECRET"), other.text());

        Path inHome = Path.of(System.getProperty("user.home"), ".tantalum-mxcit-" + UUID.randomUUID() + ".txt");
        Files.writeString(inHome, "HOME-SECRET\n");
        try {
            LocalResult home = bash(sandbox, "cat " + inHome);
            assertTrue(home.error() && !home.text().contains("HOME-SECRET"), home.text());
            LocalResult listed = bash(sandbox, "ls " + inHome.getParent());
            assertTrue(listed.error() && !listed.text().contains(inHome.getFileName().toString()), listed.text());
        } finally {
            Files.deleteIfExists(inHome);
        }

        Path ssh = Path.of(System.getProperty("user.home"), ".ssh");
        if (Files.isDirectory(ssh)) {
            LocalResult keys = bash(sandbox, "ls -la " + ssh);
            assertTrue(keys.error(), keys.text());
        }
    }

    /** 没有 --allow-write：什么都写不了（只有调用自己的临时目录，结束后删掉）。 */
    @Test
    void withoutWriteNothingCanBeWritten() throws Exception {
        Sandbox sandbox = sandbox(false);
        LocalResult inside = bash(sandbox, "echo x > b.txt");
        assertTrue(inside.error(), inside.text());
        assertFalse(Files.exists(work.resolve("b.txt")));
        assertTrue(bash(sandbox, "echo x > " + outside.resolve("b.txt")).error());
        assertFalse(Files.exists(outside.resolve("b.txt")));
        assertTrue(bash(sandbox, "echo x > \"$HOME/b.txt\"").error(), "the sandbox home is read-only");
        assertEquals("ok", bash(sandbox, "echo x > \"$TMPDIR/scratch\" && echo ok").text());
    }

    /** 有 --allow-write：只能写允许的目录和临时目录；临时目录调用结束后就没了。 */
    @Test
    void withWriteOnlyTheAllowedDirectoriesAreWritable() throws Exception {
        Sandbox sandbox = sandbox(true);
        assertFalse(bash(sandbox, "echo x > b.txt").error());
        assertEquals("x\n", Files.readString(work.resolve("b.txt")));
        LocalResult escape = bash(sandbox, "echo x > " + outside.resolve("b.txt"));
        assertTrue(escape.error(), escape.text());
        assertFalse(Files.exists(outside.resolve("b.txt")));

        String tmpdir = bash(sandbox, "echo x > \"$TMPDIR/left\" && echo \"$TMPDIR\"").text().strip();
        assertFalse(Files.exists(Path.of(tmpdir)), "the run's temp directory is deleted: " + tmpdir);
    }

    /** --no-network：连不上外网。先找一个这台电脑本身连得上的网址（下载依赖用的 Maven 中央仓库也算），都连不上就测不出东西。 */
    @Test
    void noNetworkMeansNoInternet() throws Exception {
        String reachable = null;
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            for (String url : List.of("https://example.com/", "https://repo.maven.apache.org/maven2/")) {
                try {
                    client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8)).build(),
                            HttpResponse.BodyHandlers.discarding());
                    reachable = url;
                    break;
                } catch (IOException unreachable) {
                    // 试下一个
                }
            }
        }
        assumeTrue(reachable != null, "这台电脑现在上不了网，测不出 --no-network");
        String curl = "curl -s -m 8 -o /dev/null -w '%{http_code}' " + reachable;
        LocalResult closed = bash(sandbox(false, false, List.of(), Map.of()), curl);
        assertTrue(closed.error(), closed.text());
        LocalResult open = bash(sandbox(false), curl);
        assertFalse(open.error(), "with the network on the same request works: " + open.text());
    }

    /** 网络开着也连不上本机的端口：命令碰不到 Tantalum 自己的接口。 */
    @Test
    void localServersAreNeverReachable() throws Exception {
        AtomicInteger accepted = new AtomicInteger();
        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            Thread.ofVirtual().start(() -> {
                try (Socket socket = server.accept()) {
                    accepted.incrementAndGet();
                } catch (IOException closed) {
                    // 测试结束
                }
            });
            LocalResult result = bash(sandbox(false), "exec 3<>/dev/tcp/127.0.0.1/" + server.getLocalPort() + " && echo CONNECTED");
            assertTrue(result.error() && !result.text().contains("CONNECTED"), result.text());
        }
        assertEquals(0, accepted.get());
    }

    /** 超时：返回截断前的输出和说明；后台进程也结束了，没有残留。 */
    @Test
    void aTimeoutStopsTheWholeTree() throws Exception {
        String marker = "4" + (System.nanoTime() % 100_000 + 100_000);
        LocalResult result = bash(sandbox(false), "echo started; sleep " + marker + " & sleep " + marker, "timeout", 2);
        assertTrue(result.error(), result.text());
        assertTrue(result.text().startsWith("started"), result.text());
        assertTrue(result.text().endsWith("Command timed out after 2 s. It and everything it started were stopped."), result.text());
        assertFalse(result.text().contains("backend_error"), result.text());
        assertNoProcess("sleep " + marker);
    }

    /** 取消（线程被打断，断开时也是这样）：先结束子孙进程再结束 MXC，后台进程也不留。 */
    @Test
    void cancellingStopsTheWholeTree() throws Exception {
        String marker = "5" + (System.nanoTime() % 100_000 + 100_000);
        Sandbox sandbox = sandbox(true);
        CompletableFuture<Throwable> ended = new CompletableFuture<>();
        Thread worker = Thread.ofVirtual().start(() -> {
            try {
                bash(sandbox, "touch started; sleep " + marker + " & sleep " + marker);
                ended.complete(null);
            } catch (Throwable stopped) {
                ended.complete(stopped);
            }
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!Files.exists(work.resolve("started")) || count("sleep " + marker) < 2) {
            assertTrue(System.nanoTime() < deadline, "the command never started");
            Thread.sleep(20);
        }
        worker.interrupt();
        assertTrue(ended.get(10, TimeUnit.SECONDS) instanceof InterruptedException);
        assertNoProcess("sleep " + marker);
    }

    /** 退出码不是 0 算错误；输出只留最后 2000 行并写明截断。 */
    @Test
    void exitCodesAndLongOutput() throws Exception {
        Sandbox sandbox = sandbox(false);
        LocalResult failed = bash(sandbox, "echo oops >&2; exit 3");
        assertTrue(failed.error());
        assertEquals("oops\n\nCommand exited with code 3.", failed.text());

        LocalResult longOutput = bash(sandbox, "seq 1 5000");
        assertFalse(longOutput.error());
        assertTrue(longOutput.text().startsWith("3001\n"), longOutput.text().substring(0, 20));
        assertTrue(longOutput.text().endsWith("5000\n\n[Output truncated: showing the last 2000 of 5000 lines (limit 2000 lines or 50 KB).]"));
        assertEquals("(no output)", bash(sandbox, "true").text());
    }

    /** 环境变量：HOME 是连接程序自己的目录，TMPDIR 是这次的临时目录；令牌和像密钥的变量不在里面，--env 放行的在。 */
    @Test
    void theEnvironmentHasNoSecrets() throws Exception {
        Map<String, String> host = Map.of("TANTALUM_TOKEN", "tc_should_never_show", "MY_API_KEY", "k-should-never-show",
                "VISIBLE_SETTING", "visible");
        Sandbox sandbox = sandbox(false, true, List.of("TANTALUM_TOKEN", "MY_API_KEY", "VISIBLE_SETTING"), host);
        String env = bash(sandbox, "env").text();
        assertFalse(env.contains("TANTALUM_TOKEN") || env.contains("should_never_show") || env.contains("should-never-show"), env);
        assertTrue(env.contains("VISIBLE_SETTING=visible"), env);
        assertFalse(env.contains("HOME=" + System.getProperty("user.home") + "\n"), env);
        assertTrue(env.contains("TMPDIR="), env);
        for (String line : env.split("\n")) {
            String name = line.contains("=") ? line.substring(0, line.indexOf('=')) : line;
            assertFalse(MxcPolicy.secret(name), line);
        }
    }

    /** 工具链能用：/opt/homebrew 只读放开，git 有可写的 TMPDIR、读 HOME 不报错。 */
    @Test
    void gitAndHomebrewToolsWork() throws Exception {
        Sandbox sandbox = sandbox(true);
        LocalResult git = bash(sandbox, "git init -q . && git -c user.email=a@b -c user.name=a commit -q --allow-empty -m first "
                + "&& git log --format=%s");
        assertEquals("first", git.text(), git.text());
        if (Files.isDirectory(Path.of("/opt/homebrew/bin"))) {
            LocalResult brew = bash(sandbox, "ls /opt/homebrew/bin | head -1");
            assertFalse(brew.error(), brew.text());
        }
    }

    private static int count(String pattern) throws IOException, InterruptedException {
        Process pgrep = new ProcessBuilder("pgrep", "-f", pattern).redirectErrorStream(true).start();
        String found = new String(pgrep.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        pgrep.waitFor();
        try (Stream<String> lines = found.lines()) {
            return (int) lines.filter(line -> !line.isBlank()).count();
        }
    }

    private static void assertNoProcess(String pattern) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (count(pattern) > 0 && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertEquals(0, count(pattern), "no '" + pattern + "' process is left");
    }
}
