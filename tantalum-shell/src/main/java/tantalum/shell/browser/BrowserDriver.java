package tantalum.shell.browser;

import com.microsoft.playwright.impl.driver.jar.DriverJar;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.shell.cli.CliText;

/**
 * Playwright 驱动（web-tools 设计“{@code BrowserDriver}”）：第一次用时把 jar 里的驱动解压到 {@code <数据目录>/browser/driver-<版本>/}，
 * 之后每次 {@code Playwright.create} 都直接用它（实测 195 ms；不预先解压时每次把 130 MB 解到临时目录，要 1.6–1.8 s）。
 * 内容是 {@code driver} jar 里的 {@code driver/package/**} 加 {@code driver-bundle} 里本平台的 Node，解压交给 Playwright 自己的
 * {@link DriverJar#installDriverTo}（它认得 Spring Boot 可执行 jar 里的嵌套 jar）。
 * <p>先解到同目录下的临时名字，完整了再改名，中途失败不留半个目录。碰的只是服务器自己的程序文件，不是用户的东西
 * （ArchitectureTest 规则 4 的唯一例外）。浏览器本身由 {@link #install} 装到 Playwright 的默认位置。
 * <p>Playwright 在一个进程里只认第一次用到的驱动目录（它的 {@code Driver} 是单例）。所以服务运行中这个目录不能被删：
 * 用新版本的 jar 对同一个数据目录跑 {@code browser install}，会删掉旧版本的目录，旧版本的服务要重启后浏览器才能再起。
 */
public final class BrowserDriver {

    private static final Logger LOG = LoggerFactory.getLogger(BrowserDriver.class);
    /** Playwright 认的系统属性：设了就用这个目录里的 {@code node} 和 {@code package/cli.js}，不再往临时目录解压。 */
    static final String CLI_DIR = "playwright.cli.dir";
    private static final String PREFIX = "driver-";
    private static final String PART = ".part-";
    /** 别的进程（比如同时跑的 {@code browser install}）可能正在解压；这么久没动的临时目录才算残留。 */
    private static final Duration STALE_PART = Duration.ofMinutes(10);

    /** 把驱动解压进一个新目录。测试里换成会中途失败的。 */
    interface Unpack {
        void into(Path dir) throws IOException;
    }

    private final Path root;
    private final String version;
    private final Unpack unpack;
    private Path ready;

    public BrowserDriver(Path dataDir) {
        this(dataDir, version(), BrowserDriver::fromJars);
    }

    BrowserDriver(Path dataDir, String version, Unpack unpack) {
        this.root = dataDir.resolve("browser").toAbsolutePath().normalize();
        this.version = version;
        this.unpack = unpack;
    }

    /** 驱动目录，没有就解压；然后设 {@code playwright.cli.dir}，删掉别的版本的目录。重复调用不重新解压。 */
    public synchronized Path ensure() throws IOException {
        Path target = root.resolve(PREFIX + version);
        if (ready == null || !complete(target)) {
            if (!complete(target)) {
                unpackTo(target);
            }
            removeOthers(target);
            ready = target;
        }
        System.setProperty(CLI_DIR, target.toString());
        return target;
    }

    private void unpackTo(Path target) throws IOException {
        Files.createDirectories(root);
        if (Files.exists(target)) {
            delete(target);
        }
        Path part = root.resolve(PREFIX + version + PART + UUID.randomUUID());
        long started = System.nanoTime();
        try {
            unpack.into(part);
            Path node = node(part);
            if (Files.isRegularFile(node) && !Files.isExecutable(node) && !node.toFile().setExecutable(true, false)) {
                throw new IOException("cannot make the bundled Node.js executable");
            }
            if (!complete(part)) {
                throw new IOException("the Playwright driver in the jar is incomplete");
            }
            try {
                Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (FileAlreadyExistsException other) {
                // 另一个进程刚解压好同一个版本，用它的
                if (!complete(target)) {
                    throw other;
                }
            }
            LOG.info("browser: unpacked the Playwright driver {} in {} ms", version,
                    Duration.ofNanos(System.nanoTime() - started).toMillis());
        } finally {
            if (Files.exists(part)) {
                delete(part);
            }
        }
    }

    /** 别的版本的目录、很久没动的临时目录都删掉；正在用的和别人刚开始解压的留着。 */
    private void removeOthers(Path keep) {
        List<Path> others = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root, PREFIX + "*")) {
            entries.forEach(others::add);
        } catch (IOException unreadable) {
            return;
        }
        Instant stale = Instant.now().minus(STALE_PART);
        for (Path other : others) {
            if (other.equals(keep)) {
                continue;
            }
            try {
                boolean part = other.getFileName().toString().contains(PART);
                if (!part || Files.getLastModifiedTime(other).toInstant().isBefore(stale)) {
                    delete(other);
                }
            } catch (IOException busy) {
                LOG.warn("browser: cannot remove the old driver directory {}", other.getFileName());
            }
        }
    }

    static boolean complete(Path dir) {
        return Files.isExecutable(node(dir)) && Files.isRegularFile(dir.resolve("package").resolve("cli.js"));
    }

    private static Path node(Path dir) {
        return dir.resolve(System.getProperty("os.name", "").toLowerCase().contains("windows") ? "node.exe" : "node");
    }

    /**
     * {@code browser install}：先 {@link #ensure}，再用驱动的命令行装 Chromium headless shell（约 200 MB）到 Playwright 的默认位置；
     * {@code withDeps} 时同时装 Linux 的系统库（要 root）。返回命令的退出码。
     */
    public int install(boolean withDeps, PrintStream out, CliText text) throws IOException, InterruptedException {
        Path dir = ensure();
        ProcessBuilder builder = new ProcessBuilder(installCommand(dir, withDeps)).inheritIO();
        builder.environment().remove("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD");
        out.println(text.say(CliText.Key.BROWSER_INSTALLING, Map.of("version", version)));
        long started = System.nanoTime();
        int code = builder.start().waitFor();
        out.println(code == 0 ? text.say(CliText.Key.BROWSER_DONE, Map.of("seconds", Duration.ofNanos(System.nanoTime() - started).toSeconds()))
                : text.say(CliText.Key.BROWSER_FAILED, Map.of("code", code)));
        return code;
    }

    /** {@code --no-remove}：不顺手删掉这台机器上别的程序还在用的旧版浏览器（Playwright 默认会删“没人用的”）。 */
    static List<String> installCommand(Path dir, boolean withDeps) {
        List<String> command = new ArrayList<>(List.of(node(dir).toString(), dir.resolve("package").resolve("cli.js").toString(),
                "install", "--no-remove"));
        if (withDeps) {
            command.add("--with-deps");
        }
        command.add("chromium-headless-shell");
        return command;
    }

    static void fromJars(Path dir) throws IOException {
        try {
            DriverJar.installDriverTo(dir);
        } catch (URISyntaxException | RuntimeException failed) {
            throw new IOException("cannot unpack the Playwright driver: " + failed.getMessage(), failed);
        }
    }

    /** {@code driver} jar 的 Maven 版本（1.64.0），和 Playwright for Java 的版本一致。 */
    static String version() {
        try (InputStream in = DriverJar.class.getResourceAsStream("/META-INF/maven/com.microsoft.playwright/driver/pom.properties")) {
            Properties properties = new Properties();
            if (in != null) {
                properties.load(in);
            }
            return properties.getProperty("version", "unknown");
        } catch (IOException unreadable) {
            return "unknown";
        }
    }

    private static void delete(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
