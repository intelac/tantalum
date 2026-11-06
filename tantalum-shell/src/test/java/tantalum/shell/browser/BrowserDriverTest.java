package tantalum.shell.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.cli.CliText;
import tantalum.value.text.Language;

/** 设计“{@code BrowserDriver}”：驱动解到数据目录，完整了才改名；旧版本删掉；只解一次。{@code browser install} 的参数。 */
class BrowserDriverTest {

    @TempDir
    Path temp;

    private static List<String> names(Path dir) throws IOException {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    /** 假的驱动：一个 node 和 package/cli.js，够 {@link BrowserDriver#complete} 认。 */
    private static void fake(Path dir) throws IOException {
        Files.createDirectories(dir.resolve("package"));
        Files.writeString(dir.resolve("package/cli.js"), "// cli");
        Files.writeString(dir.resolve("node"), "#!/bin/sh\n");
    }

    /** 真实的 jar：目录完整、node 能执行；第二次 ensure 不再解压，并且设了 playwright.cli.dir。 */
    @Test
    void unpacksTheRealDriverOnceAndPointsPlaywrightAtIt() throws Exception {
        AtomicInteger unpacked = new AtomicInteger();
        BrowserDriver real = new BrowserDriver(temp);
        BrowserDriver driver = new BrowserDriver(temp, BrowserDriver.version(), dir -> {
            unpacked.incrementAndGet();
            BrowserDriver.fromJars(dir);
        });
        Path dir = driver.ensure();
        assertEquals(temp.resolve("browser").resolve("driver-1.64.0").toAbsolutePath(), dir);
        assertTrue(Files.isExecutable(dir.resolve("node")), "the bundled Node.js is executable");
        assertTrue(Files.isRegularFile(dir.resolve("package/cli.js")));
        assertTrue(Files.isRegularFile(dir.resolve("package/browsers.json")));
        assertEquals(dir.toString(), System.getProperty(BrowserDriver.CLI_DIR));
        assertEquals(dir, driver.ensure());
        assertEquals(dir, real.ensure(), "a fresh instance reuses the unpacked directory");
        assertEquals(1, unpacked.get());
        assertEquals(List.of("driver-1.64.0"), names(temp.resolve("browser")));
    }

    /** 中途失败：临时目录被清掉，不留半个驱动目录；下一次重新解压。 */
    @Test
    void aFailedUnpackLeavesNoHalfDirectory() throws Exception {
        BrowserDriver failing = new BrowserDriver(temp, "9.9.9", dir -> {
            Files.createDirectories(dir.resolve("package"));
            Files.writeString(dir.resolve("package/cli.js"), "partial");
            throw new IOException("disk full");
        });
        assertEquals("disk full", assertThrows(IOException.class, failing::ensure).getMessage());
        assertEquals(List.of(), names(temp.resolve("browser")));

        BrowserDriver incomplete = new BrowserDriver(temp, "9.9.9", dir -> Files.createDirectories(dir.resolve("package")));
        assertThrows(IOException.class, incomplete::ensure);
        assertEquals(List.of(), names(temp.resolve("browser")));

        Path ok = new BrowserDriver(temp, "9.9.9", BrowserDriverTest::fake).ensure();
        assertTrue(BrowserDriver.complete(ok));
    }

    /** 别的版本的目录和很久没动的临时目录删掉；别人刚开始解压的临时目录留着。 */
    @Test
    void otherVersionsAndStalePartsAreRemoved() throws Exception {
        Path browser = temp.resolve("browser");
        fake(browser.resolve("driver-1.53.0"));
        Path stale = browser.resolve("driver-9.9.9.part-old");
        fake(stale);
        Files.setLastModifiedTime(stale, FileTime.from(Instant.now().minusSeconds(3600)));
        fake(browser.resolve("driver-9.9.9.part-fresh"));
        Files.writeString(browser.resolve("notes.txt"), "not ours");
        new BrowserDriver(temp, "9.9.9", BrowserDriverTest::fake).ensure();
        assertEquals(List.of("driver-9.9.9", "driver-9.9.9.part-fresh", "notes.txt"), names(browser));
    }

    @Test
    void installCommandKeepsOtherBrowsersAndAddsDepsOnRequest() {
        Path dir = Path.of("/data/browser/driver-1.64.0");
        assertEquals(List.of("/data/browser/driver-1.64.0/node", "/data/browser/driver-1.64.0/package/cli.js", "install",
                "--no-remove", "chromium-headless-shell"), BrowserDriver.installCommand(dir, false));
        assertTrue(BrowserDriver.installCommand(dir, true).contains("--with-deps"));
    }

    @Test
    void browserInstallParsesItsArgumentsAndRejectsUnknownOnes() throws Exception {
        assertEquals(Optional.of(false), BrowserCommand.parse(new String[] {"install"}));
        assertEquals(Optional.of(true), BrowserCommand.parse(new String[] {"install", "--with-deps"}));
        for (String[] bad : List.of(new String[] {}, new String[] {"uninstall"}, new String[] {"install", "--force"},
                new String[] {"install", "--with-deps", "x"})) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            assertEquals(2, BrowserCommand.run(bad, temp, new PrintStream(out, true, StandardCharsets.UTF_8), CliText.of(Language.EN)));
            assertEquals("usage: browser install [--with-deps] [--lang zh|en|ja]", out.toString(StandardCharsets.UTF_8).strip());
        }
        assertFalse(Files.exists(temp.resolve("browser")), "bad arguments unpack nothing");
    }
}
