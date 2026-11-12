package tantalum.connect.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * local-browser 需求 2.7（搬自服务器的 BrowserDriverTest）：驱动解到连接程序自己的目录，完整了才改名；旧版本删掉；只解一次；
 * 安装包里带了驱动时直接用包里的，不解压。
 */
class DriverTest {

    @TempDir
    Path temp;

    private static Driver driver(Path home, String version, Driver.Unpack unpack) {
        return new Driver(home, version, unpack, null);
    }

    private static List<String> names(Path dir) throws IOException {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    /** 假的驱动：这个平台的 node（Windows 上是 node.exe）和 package/cli.js，够 {@link Driver#complete} 认。 */
    private static void fake(Path dir) throws IOException {
        Files.createDirectories(dir.resolve("package"));
        Files.writeString(dir.resolve("package/cli.js"), "// cli");
        Files.writeString(Driver.node(dir, Driver.WINDOWS), "#!/bin/sh\n").toFile().setExecutable(true, false);
    }

    /** 真实的 jar：目录完整、node 能执行；第二次 ensure 不再解压，并且设了 playwright.cli.dir。 */
    @Test
    void unpacksTheRealDriverOnceAndPointsPlaywrightAtIt() throws Exception {
        AtomicInteger unpacked = new AtomicInteger();
        Driver real = driver(temp, Driver.version(), Driver::fromJars);
        Driver driver = driver(temp, Driver.version(), dir -> {
            unpacked.incrementAndGet();
            Driver.fromJars(dir);
        });
        Path dir = driver.ensure();
        assertEquals(temp.resolve("browser-driver-1.64.0").toAbsolutePath(), dir);
        assertTrue(Files.isExecutable(Driver.node(dir, Driver.WINDOWS)), "the bundled Node.js is executable");
        assertTrue(Files.isRegularFile(dir.resolve("package/cli.js")));
        assertTrue(Files.isRegularFile(dir.resolve("package/browsers.json")));
        assertEquals(dir.toString(), System.getProperty(Driver.CLI_DIR));
        assertEquals(dir, driver.ensure());
        assertEquals(dir, real.ensure(), "a fresh instance reuses the unpacked directory");
        assertEquals(1, unpacked.get());
        assertEquals(List.of("browser-driver-1.64.0"), names(temp));
    }

    /** 中途失败：临时目录被清掉，不留半个驱动目录；下一次重新解压。 */
    @Test
    void aFailedUnpackLeavesNoHalfDirectory() throws Exception {
        Driver failing = driver(temp, "9.9.9", dir -> {
            Files.createDirectories(dir.resolve("package"));
            Files.writeString(dir.resolve("package/cli.js"), "partial");
            throw new IOException("disk full");
        });
        assertEquals("disk full", assertThrows(IOException.class, failing::ensure).getMessage());
        assertEquals(List.of(), names(temp));

        Driver incomplete = driver(temp, "9.9.9", dir -> Files.createDirectories(dir.resolve("package")));
        assertThrows(IOException.class, incomplete::ensure);
        assertEquals(List.of(), names(temp));

        Path ok = driver(temp, "9.9.9", DriverTest::fake).ensure();
        assertTrue(Driver.complete(ok));
    }

    /** 别的版本的目录和很久没动的临时目录删掉；别人刚开始解压的临时目录留着。 */
    @Test
    void otherVersionsAndStalePartsAreRemoved() throws Exception {
        Path browser = temp;
        fake(browser.resolve("browser-driver-1.53.0"));
        Path stale = browser.resolve("browser-driver-9.9.9.part-old");
        fake(stale);
        Files.setLastModifiedTime(stale, FileTime.from(Instant.now().minusSeconds(3600)));
        fake(browser.resolve("browser-driver-9.9.9.part-fresh"));
        Files.createDirectories(browser.resolve("browser-profile"));
        Files.writeString(browser.resolve("notes.txt"), "not ours");
        driver(temp, "9.9.9", DriverTest::fake).ensure();
        assertEquals(List.of("browser-driver-9.9.9", "browser-driver-9.9.9.part-fresh", "browser-profile", "notes.txt"), names(browser),
                "the profile directory next to it is never touched");
    }

    /**
     * windows-server 需求 5.4：两个进程同时解压，另一个先把同一个版本解好了。改名到已存在的目录在 macOS、Linux 上报
     * DirectoryNotEmpty 或 FileAlreadyExists，在 Windows 上报 AccessDenied；目标完整就用它，不报错，自己的临时目录删掉。
     */
    @Test
    void whenAnotherProcessUnpackedTheSameVersionFirstItsDirectoryIsUsed() throws Exception {
        Path target = temp.resolve("browser-driver-9.9.9");
        Driver driver = driver(temp, "9.9.9", part -> {
            fake(part);
            fake(target);
            Files.writeString(target.resolve("theirs.txt"), "unpacked by the other process");
        });
        assertEquals(target, driver.ensure());
        assertTrue(Files.isRegularFile(target.resolve("theirs.txt")));
        assertEquals(List.of("browser-driver-9.9.9"), names(temp));
    }

    /** 别人留下的目标不完整时照样失败，报改名时的原错误，不把半个目录当成驱动。 */
    @Test
    void anIncompleteDirectoryInTheWayIsNotUsed() throws Exception {
        Path target = temp.resolve("browser-driver-9.9.9");
        Driver driver = driver(temp, "9.9.9", part -> {
            fake(part);
            Files.createDirectories(target.resolve("package"));
        });
        assertThrows(FileSystemException.class, driver::ensure);
        assertFalse(Driver.complete(target));
        assertEquals(List.of("browser-driver-9.9.9"), names(temp), "the temporary directory is removed");
    }



    /** 安装包：启动器指到包里解压好的驱动，直接用它，不往连接程序的目录里解压；包里的不完整就报错，不退回去解压。 */
    @Test
    void aDriverBundledWithThePackageIsUsedAsIs() throws Exception {
        Path bundled = temp.resolve("app").resolve("browser-driver");
        fake(bundled);
        Path home = temp.resolve("home");
        Driver driver = new Driver(home, "9.9.9", dir -> {
            throw new IOException("must not unpack");
        }, bundled);
        assertEquals(bundled, driver.ensure());
        assertEquals(bundled.toString(), System.getProperty(Driver.CLI_DIR));
        assertFalse(Files.exists(home), "nothing is unpacked next to the profile");

        Files.delete(bundled.resolve("package/cli.js"));
        assertThrows(IOException.class, driver::ensure);
    }
}
