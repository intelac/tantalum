package tantalum.shell.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.cli.CliText;
import tantalum.value.text.Language;

/** local-browser 需求 8.2–8.4：服务器上的浏览器删掉之后的收尾。 */
class RetiredBrowserTest {

    @TempDir
    Path data;

    /** 需求 8.2：旧配置里还写着的键找得出来（只有键名），没写的不算。 */
    @Test
    void staleSettingsAreNamed() {
        assertEquals(List.of(), RetiredBrowser.staleKeys(key -> false));
        Set<String> configured = Set.of("tantalum.web.browser.max-open", "tantalum.web.browser.sandbox", "tantalum.web.fetch.enabled");
        assertEquals(List.of("tantalum.web.browser.max-open", "tantalum.web.browser.sandbox"), RetiredBrowser.staleKeys(configured::contains));
    }

    /** 需求 8.4：以前解压的驱动目录删掉；数据目录里别的东西一样不动。 */
    @Test
    void theOldDriverDirectoryIsRemovedAndNothingElse() throws Exception {
        Path driver = Files.createDirectories(data.resolve("browser").resolve("driver-1.64.0").resolve("package"));
        Files.writeString(driver.resolve("cli.js"), "// cli");
        Files.writeString(data.resolve("browser").resolve("driver-1.64.0").resolve("node"), "node");
        Files.createDirectories(data.resolve("browser").resolve("driver-1.53.0.part-abc"));
        Path journal = Files.createDirectories(data.resolve("sessions"));
        Files.writeString(journal.resolve("s1.jsonl"), "{}");

        RetiredBrowser.atStartup(key -> key.equals("tantalum.web.browser.enabled"), data);
        assertFalse(Files.exists(data.resolve("browser")));
        assertEquals("{}", Files.readString(journal.resolve("s1.jsonl")));
        assertFalse(RetiredBrowser.removeOldDriver(data), "nothing left to remove; a second start is quiet");
    }

    /** 那个目录里有别的东西（不是我们以前放的）、或者它是个符号链接：不碰。 */
    @Test
    void aDirectoryThatIsNotTheOldDriverIsLeftAlone() throws Exception {
        Path browser = Files.createDirectories(data.resolve("browser"));
        Files.createDirectories(browser.resolve("driver-1.64.0"));
        Files.writeString(browser.resolve("my-notes.txt"), "keep");
        assertFalse(RetiredBrowser.removeOldDriver(data));
        assertEquals("keep", Files.readString(browser.resolve("my-notes.txt")));
        assertTrue(Files.isDirectory(browser.resolve("driver-1.64.0")));

        Path other = data.resolve("other-data");
        Path elsewhere = Files.createDirectories(other.resolve("target"));
        Files.createDirectories(elsewhere.resolve("driver-9"));
        try {
            Files.createSymbolicLink(other.resolve("browser"), elsewhere);
        } catch (java.io.IOException | UnsupportedOperationException noLinks) {
            return; // Windows 上没有权限建符号链接时这一半不测
        }
        assertFalse(RetiredBrowser.removeOldDriver(other));
        assertTrue(Files.isDirectory(elsewhere.resolve("driver-9")), "a link is never followed");
    }

    /** 需求 8.3：`browser` 命令只剩一句说明，三种语言都写明浏览器在用户电脑上、要加 --allow-browser。 */
    @Test
    void theBrowserCommandOnlyExplainsWhereTheBrowserWent() {
        for (Language language : Language.values()) {
            String text = CliText.of(language).say(CliText.Key.BROWSER_MOVED);
            assertTrue(text.contains("--allow-browser"), text);
            assertFalse(text.contains("install [--with-deps]"), text);
        }
        assertTrue(CliText.of(Language.ZH).say(CliText.Key.BROWSER_MOVED).contains("不在服务器上"));
    }
}
