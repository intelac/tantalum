package tantalum.connect.browser;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 在这台电脑的标准安装位置找 Google Chrome 和 Microsoft Edge（local-browser 需求 2）。只看文件在不在、能不能执行，
 * 不启动浏览器，也不下载。先 Chrome 后 Edge；指定了一个就只找那一个。
 */
public final class Installed {

    /** {@code --browser} 的值。 */
    public enum Choice {
        ANY, CHROME, EDGE;

        /** {@code chrome}、{@code edge}；别的值为空。 */
        public static Optional<Choice> parse(String value) {
            return switch (value == null ? "" : value.toLowerCase(Locale.ROOT)) {
                case "chrome" -> Optional.of(CHROME);
                case "edge" -> Optional.of(EDGE);
                default -> Optional.empty();
            };
        }
    }

    /** 找到的浏览器：给人看的名字和可执行文件。 */
    public record Found(String name, Path path) {}

    static final String CHROME = "Google Chrome";
    static final String EDGE = "Microsoft Edge";

    private Installed() {}

    public static Optional<Found> find(Choice choice) {
        return find(choice, System.getProperty("os.name", ""), System.getenv(), Path.of(System.getProperty("user.home", "")), Path.of("/"));
    }

    /**
     * @param env Windows 上从这里取 {@code ProgramFiles}、{@code ProgramFiles(x86)}、{@code LOCALAPPDATA}
     * @param home 家目录（macOS 上的 {@code ~/Applications}）
     * @param root macOS、Linux 上的 {@code /}；测试里换成临时目录
     */
    static Optional<Found> find(Choice choice, String os, Map<String, String> env, Path home, Path root) {
        List<Found> candidates = new ArrayList<>();
        if (choice != Choice.EDGE) {
            chrome(os, env, home, root).forEach(path -> candidates.add(new Found(CHROME, path)));
        }
        if (choice != Choice.CHROME) {
            edge(os, env, home, root).forEach(path -> candidates.add(new Found(EDGE, path)));
        }
        return candidates.stream().filter(found -> usable(found.path())).findFirst();
    }

    /** 是个文件而且能执行。{@code --browser-path} 也用它查。 */
    public static boolean usable(Path file) {
        return Files.isRegularFile(file) && Files.isExecutable(file);
    }

    private static List<Path> chrome(String os, Map<String, String> env, Path home, Path root) {
        if (mac(os)) {
            String app = "Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
            return List.of(root.resolve(app), home.resolve(app));
        }
        if (windows(os)) {
            return programs(env, "Google\\Chrome\\Application\\chrome.exe");
        }
        return List.of(root.resolve("usr/bin/google-chrome"), root.resolve("usr/bin/google-chrome-stable"));
    }

    private static List<Path> edge(String os, Map<String, String> env, Path home, Path root) {
        if (mac(os)) {
            String app = "Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge";
            return List.of(root.resolve(app), home.resolve(app));
        }
        if (windows(os)) {
            return programs(env, "Microsoft\\Edge\\Application\\msedge.exe");
        }
        return List.of(root.resolve("usr/bin/microsoft-edge"), root.resolve("usr/bin/microsoft-edge-stable"));
    }

    /** Windows 上程序可能装在的三处；环境变量没有或写得不成路径的那一处跳过。 */
    private static List<Path> programs(Map<String, String> env, String relative) {
        List<Path> paths = new ArrayList<>();
        for (String variable : List.of("ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA")) {
            String base = env.get(variable);
            if (base == null || base.isBlank()) {
                continue;
            }
            try {
                Path dir = Path.of(base);
                for (String part : relative.split("\\\\")) {
                    dir = dir.resolve(part);
                }
                paths.add(dir);
            } catch (InvalidPathException unusable) {
                // 这一处不看
            }
        }
        return paths;
    }

    static boolean mac(String os) {
        return os.toLowerCase(Locale.ROOT).contains("mac");
    }

    static boolean windows(String os) {
        return os.toLowerCase(Locale.ROOT).startsWith("windows");
    }
}
