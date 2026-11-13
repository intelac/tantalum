package tantalum.connect.browser;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * 找电脑上的 {@code npx}（浏览器靠它跑 Playwright MCP，connector-jar 需求 7.2）：PATH 里的 {@code npx}，Windows 上是
 * {@code npx.cmd}。macOS 从程序坞启动的程序 PATH 很短，再看几个常见位置（Homebrew、官方安装包）。
 */
public final class NodeFinder {

    private NodeFinder() {}

    public static Optional<Path> npx() {
        return npx(System.getenv(), System.getProperty("os.name", ""), Files::isRegularFile);
    }

    static Optional<Path> npx(Map<String, String> env, String os, Predicate<Path> exists) {
        boolean windows = os.toLowerCase(Locale.ROOT).startsWith("windows");
        String name = windows ? "npx.cmd" : "npx";
        String path = env.get("PATH");
        if (path == null) {
            path = env.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase("PATH")).map(Map.Entry::getValue)
                    .findFirst().orElse("");
        }
        for (String entry : path.split(windows ? ";" : File.pathSeparator)) {
            String dir = entry.strip().replace("\"", "");
            if (!dir.isEmpty()) {
                Path candidate = Path.of(dir, name);
                if (exists.test(candidate)) {
                    return Optional.of(candidate);
                }
            }
        }
        if (!windows) {
            for (String dir : new String[] {"/opt/homebrew/bin", "/usr/local/bin"}) {
                Path candidate = Path.of(dir, name);
                if (exists.test(candidate)) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }
}
