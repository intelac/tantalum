package tantalum.local.shell;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * 找 shell 程序。
 * <ul>
 *   <li>bash：{@code /bin/bash}，再找 PATH 里的 {@code bash}，最后 {@code /bin/sh}（pi {@code utils/shell.js:58-102} 在 Unix 上同样先
 *       {@code /bin/bash} 再 {@code bash} 再 {@code sh}）。</li>
 *   <li>PowerShell：{@code %ProgramFiles%\PowerShell\7\pwsh.exe}，再找 PATH 里的 {@code pwsh.exe}，最后系统自带的 5.1
 *       {@code %SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe}（DSH {@code packages/shell/pwsh-local/src/resolve.ts:21-79}）。
 *       用“存在”而不是“可执行”判断：微软商店装的 {@code pwsh.exe} 是个应用执行别名，DSH 也只用 {@code lstat}（{@code :46-56}）。</li>
 * </ul>
 * 环境变量和“文件在不在”都由参数给，测试不用真的系统。
 */
public final class ShellFinder {

    private final Map<String, String> env;
    private final Predicate<Path> exists;

    public ShellFinder(Map<String, String> env, Predicate<Path> exists) {
        this.env = env;
        this.exists = exists;
    }

    /** 用这台电脑的环境变量和文件。 */
    public static ShellFinder system() {
        return new ShellFinder(System.getenv(), path -> Files.exists(path, LinkOption.NOFOLLOW_LINKS));
    }

    public Optional<Path> find(ShellKind kind) {
        return candidates(kind).stream().filter(exists).findFirst();
    }

    /** 按顺序要试的路径。 */
    List<Path> candidates(ShellKind kind) {
        List<Path> paths = new ArrayList<>();
        if (kind == ShellKind.POWERSHELL) {
            String programFiles = get("ProgramFiles");
            if (programFiles != null) {
                paths.add(Path.of(programFiles, "PowerShell", "7", "pwsh.exe"));
            }
            paths.addAll(onPath("pwsh.exe"));
            String systemRoot = get("SystemRoot");
            paths.add(Path.of(systemRoot == null ? "C:\\Windows" : systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe"));
        } else {
            paths.add(Path.of("/bin/bash"));
            paths.addAll(onPath("bash"));
            paths.add(Path.of("/bin/sh"));
        }
        return paths;
    }

    /** PATH 里每一项下面的 {@code name}；去掉两头的引号（Windows 上常见），空项跳过。 */
    private List<Path> onPath(String name) {
        String path = get("PATH");
        if (path == null) {
            return List.of();
        }
        List<Path> found = new ArrayList<>();
        for (String entry : path.split(File.pathSeparator)) {
            String dir = entry.strip();
            if (dir.length() >= 2 && dir.startsWith("\"") && dir.endsWith("\"")) {
                dir = dir.substring(1, dir.length() - 1);
            }
            if (dir.isEmpty()) {
                continue;
            }
            try {
                found.add(Path.of(dir, name));
            } catch (RuntimeException invalid) {
                // PATH 里有这台电脑上不合法的路径：跳过
            }
        }
        return found;
    }

    /** Windows 的环境变量名不分大小写（{@code Path}、{@code PATH}）。 */
    private String get(String name) {
        String value = env.get(name);
        if (value != null) {
            return value;
        }
        for (Map.Entry<String, String> entry : env.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
