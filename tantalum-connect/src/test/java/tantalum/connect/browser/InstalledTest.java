package tantalum.connect.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import tantalum.connect.browser.Installed.Choice;
import tantalum.connect.browser.Installed.Found;

/** local-browser 需求 2.1、2.2、2.5、2.6：在标准位置找 Chrome 和 Edge，只看文件；先 Chrome 后 Edge；指定了就只找那一个。 */
class InstalledTest {

    private static final String MAC_CHROME = "Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
    private static final String MAC_EDGE = "Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge";

    @TempDir
    Path temp;

    private Path program(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "#!/bin/sh\n");
        assertTrue(file.toFile().setExecutable(true, false));
        return file;
    }

    private Optional<Found> onMac(Choice choice) {
        return Installed.find(choice, "Mac OS X", Map.of(), temp.resolve("home"), temp.resolve("root"));
    }

    @Test
    void onMacChromeComesFirstThenEdgeAndTheUsersOwnApplicationsFolderCounts() throws Exception {
        assertEquals(Optional.empty(), onMac(Choice.ANY), "nothing installed");
        Path edge = program(temp.resolve("root").resolve(MAC_EDGE));
        assertEquals(Optional.of(new Found("Microsoft Edge", edge)), onMac(Choice.ANY));
        assertEquals(Optional.empty(), onMac(Choice.CHROME), "--browser chrome does not fall back to Edge");
        Path chrome = program(temp.resolve("home").resolve(MAC_CHROME));
        assertEquals(Optional.of(new Found("Google Chrome", chrome)), onMac(Choice.ANY), "Chrome wins when both are there");
        assertEquals(Optional.of(new Found("Microsoft Edge", edge)), onMac(Choice.EDGE));
        Path system = program(temp.resolve("root").resolve(MAC_CHROME));
        assertEquals(Optional.of(new Found("Google Chrome", system)), onMac(Choice.CHROME), "/Applications before ~/Applications");
    }

    @Test
    void onWindowsTheThreeProgramFoldersAreSearched() throws Exception {
        Path programs = temp.resolve("Program Files");
        Path x86 = temp.resolve("Program Files (x86)");
        Path local = temp.resolve("AppData").resolve("Local");
        Map<String, String> env = Map.of("ProgramFiles", programs.toString(), "ProgramFiles(x86)", x86.toString(),
                "LOCALAPPDATA", local.toString());
        assertEquals(Optional.empty(), Installed.find(Choice.ANY, "Windows 11", env, temp, temp));
        Path edge = program(x86.resolve("Microsoft").resolve("Edge").resolve("Application").resolve("msedge.exe"));
        assertEquals(Optional.of(new Found("Microsoft Edge", edge)), Installed.find(Choice.ANY, "Windows 11", env, temp, temp));
        Path chrome = program(local.resolve("Google").resolve("Chrome").resolve("Application").resolve("chrome.exe"));
        assertEquals(Optional.of(new Found("Google Chrome", chrome)), Installed.find(Choice.ANY, "Windows 11", env, temp, temp),
                "a per-user Chrome is found too, and Chrome comes before Edge");
        assertEquals(Optional.of(new Found("Microsoft Edge", edge)), Installed.find(Choice.EDGE, "Windows Server 2022", env, temp, temp));
        // 环境变量缺了、是空白：那一处用标准位置，不出错
        assertEquals(Optional.of(new Found("Google Chrome", chrome)),
                Installed.find(Choice.ANY, "Windows 11", Map.of("LOCALAPPDATA", local.toString(), "ProgramFiles", " "), temp, temp));
    }

    /**
     * Windows 上环境变量的名字不分大小写，但 Java 给的环境变量表区分：有的启动方式把名字写成全大写（2026-11-12 Windows CI 上
     * 因此一个浏览器也没找到）。名字大小写不同照样认；变量根本没有时用系统盘上的标准位置和家目录下的 AppData\\Local。
     */
    @Test
    void onWindowsVariableNamesIgnoreCaseAndMissingOnesFallBackToTheStandardPlaces() throws Exception {
        Path programs = temp.resolve("PF");
        Path edge = program(programs.resolve("Microsoft").resolve("Edge").resolve("Application").resolve("msedge.exe"));
        assertEquals(Optional.of(new Found("Microsoft Edge", edge)),
                Installed.find(Choice.ANY, "Windows Server 2025", Map.of("PROGRAMFILES(X86)", programs.toString()), temp, temp));
        assertEquals("v", Installed.variable(Map.of("programfiles", "v"), "ProgramFiles"));
        assertEquals(null, Installed.variable(Map.of("Other", "v"), "ProgramFiles"));

        // 没有任何变量：系统盘根下的 Program Files、Program Files (x86)，家目录下的 AppData\Local
        Path root = temp.resolve("drive");
        Path home = temp.resolve("drive").resolve("Users").resolve("yi");
        assertEquals(Optional.empty(), Installed.find(Choice.ANY, "Windows 11", Map.of(), home, root));
        Path standardEdge = program(root.resolve("Program Files (x86)").resolve("Microsoft").resolve("Edge").resolve("Application")
                .resolve("msedge.exe"));
        assertEquals(Optional.of(new Found("Microsoft Edge", standardEdge)), Installed.find(Choice.ANY, "Windows 11", Map.of(), home, root));
        Path userChrome = program(home.resolve("AppData").resolve("Local").resolve("Google").resolve("Chrome").resolve("Application")
                .resolve("chrome.exe"));
        assertEquals(Optional.of(new Found("Google Chrome", userChrome)), Installed.find(Choice.ANY, "Windows 11", Map.of(), home, root));
        assertEquals(6, Installed.candidates(Choice.ANY, "Windows 11", Map.of(), home, root).size(), "three places each for Chrome and Edge");
    }

    @Test
    void onLinuxTheUsualCommandsAreSearched() throws Exception {
        Path root = temp.resolve("root");
        assertEquals(Optional.empty(), Installed.find(Choice.ANY, "Linux", Map.of(), temp, root));
        Path edge = program(root.resolve("usr/bin/microsoft-edge-stable"));
        Path chrome = program(root.resolve("usr/bin/google-chrome"));
        assertEquals(Optional.of(new Found("Google Chrome", chrome)), Installed.find(Choice.ANY, "Linux", Map.of(), temp, root));
        assertEquals(Optional.of(new Found("Microsoft Edge", edge)), Installed.find(Choice.EDGE, "Linux", Map.of(), temp, root));
    }

    /** 文件在但不能执行、或者那里是个目录：不算装了。Windows 上没有执行位，这条只在 POSIX 上有意义。 */
    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "Windows has no executable bit: every existing file counts as runnable")
    void aFileThatCannotRunDoesNotCount() throws Exception {
        Path chrome = temp.resolve("root").resolve(MAC_CHROME);
        Files.createDirectories(chrome.getParent());
        Files.writeString(chrome, "not a program");
        assertTrue(chrome.toFile().setExecutable(false, false));
        assertEquals(Optional.empty(), onMac(Choice.ANY));
        Files.delete(chrome);
        Files.createDirectory(chrome);
        assertEquals(Optional.empty(), onMac(Choice.ANY));
        assertFalse(Installed.usable(chrome));
    }

    @Test
    void theBrowserOptionTakesChromeOrEdge() {
        assertEquals(Optional.of(Choice.CHROME), Choice.parse("chrome"));
        assertEquals(Optional.of(Choice.EDGE), Choice.parse("Edge"));
        for (String other : new String[] {"firefox", "safari", "any", "", "msedge"}) {
            assertEquals(Optional.empty(), Choice.parse(other), other);
        }
        assertEquals(Optional.empty(), Choice.parse(null));
    }
}
