package tantalum.local.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 找 shell 的顺序：bash 照 pi，PowerShell 照 DSH（connector-jar 需求 4.1、4.2）。文件在不在由集合给。 */
class ShellFinderTest {

    private static Optional<Path> find(ShellKind kind, Map<String, String> env, Path... existing) {
        Set<Path> files = Set.of(existing);
        return new ShellFinder(env, files::contains).find(kind);
    }

    @Test
    void windowsPrefersPowerShell7ThenPwshOnPathThenTheBuiltIn() {
        Path seven = Path.of("C:\\Program Files", "PowerShell", "7", "pwsh.exe");
        // 不带盘符：测试也在 macOS 上跑，那里的 PATH 分隔符是冒号
        Path onPath = Path.of("tools-pwsh", "pwsh.exe");
        Path builtIn = Path.of("C:\\Windows", "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
        Map<String, String> env = Map.of("ProgramFiles", "C:\\Program Files", "Path", "\"tools-pwsh\"" + File.pathSeparator,
                "SystemRoot", "C:\\Windows");
        assertEquals(Optional.of(seven), find(ShellKind.POWERSHELL, env, seven, onPath, builtIn));
        assertEquals(Optional.of(onPath), find(ShellKind.POWERSHELL, env, onPath, builtIn), "quotes around a PATH entry are removed");
        assertEquals(Optional.of(builtIn), find(ShellKind.POWERSHELL, env, builtIn));
    }

    @Test
    void windowsEnvironmentNamesAreCaseInsensitive() {
        Path builtIn = Path.of("E:\\Win", "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
        assertEquals(Optional.of(builtIn), find(ShellKind.POWERSHELL, Map.of("SYSTEMROOT", "E:\\Win"), builtIn));
    }

    @Test
    void bashIsBinBashThenPathThenSh() {
        Path onPath = Path.of("/opt/bin", "bash");
        Map<String, String> env = Map.of("PATH", "/opt/bin" + File.pathSeparator + File.pathSeparator + "/usr/bin");
        assertEquals(Optional.of(Path.of("/bin/bash")), find(ShellKind.BASH, env, Path.of("/bin/bash"), onPath));
        assertEquals(Optional.of(onPath), find(ShellKind.BASH, env, onPath, Path.of("/bin/sh")));
        assertEquals(Optional.of(Path.of("/bin/sh")), find(ShellKind.BASH, env, Path.of("/bin/sh")));
    }

    @Test
    void nothingFoundIsEmpty() {
        assertTrue(find(ShellKind.POWERSHELL, Map.of()).isEmpty());
        assertTrue(find(ShellKind.BASH, Map.of()).isEmpty());
    }

    @Test
    void kindFollowsTheOperatingSystem() {
        assertEquals(ShellKind.POWERSHELL, ShellKind.of("Windows 11"));
        assertEquals(ShellKind.BASH, ShellKind.of("Mac OS X"));
        assertEquals(ShellKind.BASH, ShellKind.of("Linux"));
    }

    @Test
    void notFoundSaysWhichShell() {
        Shell shell = LocalShell.open(new ShellFinder(Map.of(), path -> false), ShellKind.POWERSHELL, Map.of());
        assertEquals("shell/not-found", shell.check().why().code().wire());
        assertTrue(shell.check().reason().contains("PowerShell"));
        assertEquals(ShellKind.POWERSHELL, shell.kind());
    }
}
