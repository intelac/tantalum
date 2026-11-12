package tantalum.connect.browser;

import com.microsoft.playwright.impl.driver.jar.DriverJar;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Playwright 驱动（Node 加 {@code package/cli.js}）在这台电脑上的位置（local-browser 需求 2.7）。两种来源：
 * <ul>
 *   <li>安装包：启动器经系统属性 {@value #BUNDLED} 指到包里已经解压好的目录，直接用。</li>
 *   <li>从源码或 jar 运行：第一次用浏览器时把 jar 里本平台的驱动解压到连接程序自己的目录下
 *       {@code browser-driver-<版本>/}，之后直接用（不预先解压时 Playwright 每次都往临时目录解 130 MB）。</li>
 * </ul>
 * 先解到同目录下的临时名字，完整了再改名，中途失败不留半个目录；别的版本的目录删掉。只在第一次有浏览器调用时做，
 * 连接程序启动时不碰。Playwright 在一个进程里只认第一次用到的驱动目录，所以运行中这个目录不能被删。
 */
public final class Driver {

    /** 安装包的启动器设的：包里驱动目录的位置。 */
    public static final String BUNDLED = "tantalum.browser.driver";
    /** Playwright 认的系统属性：设了就用这个目录里的 {@code node} 和 {@code package/cli.js}，不再往临时目录解压。 */
    static final String CLI_DIR = "playwright.cli.dir";
    static final boolean WINDOWS = System.getProperty("os.name", "").startsWith("Windows");
    static final String PREFIX = "browser-driver-";
    private static final String PART = ".part-";
    /** 别的进程（同一台电脑上另一个连接程序）可能正在解压；这么久没动的临时目录才算残留。 */
    private static final Duration STALE_PART = Duration.ofMinutes(10);

    /** 把驱动解压进一个新目录。测试里换成会中途失败的。 */
    interface Unpack {
        void into(Path dir) throws IOException;
    }

    private final Path root;
    private final String version;
    private final Unpack unpack;
    private final Path bundled;
    private Path ready;

    /** {@code home}：连接程序自己的目录（{@link Profile#home()}）。 */
    public Driver(Path home) {
        this(home, version(), Driver::fromJars, bundled(System.getProperty(BUNDLED)));
    }

    Driver(Path home, String version, Unpack unpack, Path bundled) {
        this.root = home.toAbsolutePath().normalize();
        this.version = version;
        this.unpack = unpack;
        this.bundled = bundled;
    }

    private static Path bundled(String property) {
        return property == null || property.isBlank() ? null : Path.of(property);
    }

    /** 驱动目录；然后设 {@code playwright.cli.dir}。包里带的优先；否则没有就解压，并删掉别的版本的目录。重复调用不重新解压。 */
    public synchronized Path ensure() throws IOException {
        if (bundled != null) {
            if (!complete(bundled)) {
                throw new IOException("the browser driver in the package is incomplete: " + bundled);
            }
            System.setProperty(CLI_DIR, bundled.toString());
            return bundled;
        }
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
            Path node = node(part, WINDOWS);
            if (Files.isRegularFile(node) && !Files.isExecutable(node) && !node.toFile().setExecutable(true, false)) {
                throw new IOException("cannot make the bundled Node.js executable");
            }
            if (!complete(part)) {
                throw new IOException("the Playwright driver in the jar is incomplete");
            }
            try {
                Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (FileSystemException other) {
                // 另一个进程刚解压好同一个版本，用它的。目标目录已存在时 macOS、Linux 报 FileAlreadyExists 或
                // DirectoryNotEmpty，Windows 报 AccessDenied，都按目标是否完整判断。
                if (!complete(target)) {
                    throw other;
                }
            }
            note("unpacked the browser driver " + version + " in " + Duration.ofNanos(System.nanoTime() - started).toMillis() + " ms");
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
                note("cannot remove the old browser driver directory " + other.getFileName());
            }
        }
    }

    static boolean complete(Path dir) {
        return Files.isExecutable(node(dir, WINDOWS)) && Files.isRegularFile(dir.resolve("package").resolve("cli.js"));
    }

    /** {@code driver-bundle} 里各平台的 Node：Windows 上是 {@code node.exe}。 */
    static Path node(Path dir, boolean windows) {
        return dir.resolve(windows ? "node.exe" : "node");
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
        try (InputStream in = Driver.class.getResourceAsStream("/META-INF/maven/com.microsoft.playwright/driver/pom.properties")) {
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

    /** 细节只打在这台电脑的终端里。 */
    private static void note(String text) {
        System.err.println("[tantalum-connect] " + text);
    }
}
