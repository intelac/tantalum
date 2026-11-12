package tantalum.connect.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** local-browser 需求 3.3–3.6：资料目录在连接程序自己的位置，不是用户平时浏览器的资料目录；只有本人能进；已有的内容不动。 */
class ProfileTest {

    @TempDir
    Path temp;

    @Test
    void theConnectorsOwnDirectoryPerSystem() {
        Path home = temp.resolve("yi");
        assertEquals(home.resolve("Library").resolve("Application Support").resolve("Tantalum Connect"),
                Profile.home("Mac OS X", Map.of(), home));
        Path local = temp.resolve("AppData").resolve("Local");
        assertEquals(local.resolve("Tantalum Connect"), Profile.home("Windows 11", Map.of("LOCALAPPDATA", local.toString()), home));
        assertEquals(home.resolve("AppData").resolve("Local").resolve("Tantalum Connect"), Profile.home("Windows 11", Map.of(), home));
        assertEquals(home.resolve(".local").resolve("share").resolve("tantalum-connect"), Profile.home("Linux", Map.of(), home));
    }

    /** 需求 3.4：哪个系统上都不落在 Chrome、Edge 自己的资料目录里。 */
    @Test
    void itIsNeverTheEverydayBrowsersProfile() {
        Path home = temp.resolve("yi");
        for (String os : new String[] {"Mac OS X", "Windows 11", "Linux"}) {
            String dir = Profile.home(os, Map.of(), home).resolve(Profile.DIR).toString();
            assertFalse(dir.contains("Google") || dir.contains("Chrome") || dir.contains("Microsoft") || dir.contains("Edge"), dir);
            assertTrue(dir.endsWith("browser-profile"), dir);
        }
    }

    @Test
    void prepareCreatesTheDirectoryAndKeepsWhatIsThere() throws Exception {
        Path dir = temp.resolve("a").resolve("b").resolve("browser-profile");
        Path real = Profile.prepare(dir);
        assertTrue(Files.isDirectory(real));
        assertEquals(dir.toRealPath(), real);
        Files.writeString(real.resolve("Cookies"), "kept");
        assertEquals(real, Profile.prepare(dir));
        assertEquals("kept", Files.readString(real.resolve("Cookies")), "an existing profile is left as it is");
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "POSIX modes; on Windows the directory inherits the user's own ACL")
    void onlyTheUserCanEnterIt() throws Exception {
        Path dir = temp.resolve("x").resolve("browser-profile");
        Profile.prepare(dir);
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)));
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.getParent())));
        // 已有的、别人也能进的目录收紧
        Path loose = Files.createDirectory(temp.resolve("loose"));
        Files.setPosixFilePermissions(loose, PosixFilePermissions.fromString("rwxr-xr-x"));
        Profile.prepare(loose);
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(loose)));
    }
}
