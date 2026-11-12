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
        return find(choice, System.getProperty("os.name", ""), System.getenv(), home(), root());
    }

    /** 这台电脑上会去看的全部位置，按查找的顺序。找不到时用它说明找过哪里。 */
    public static List<Path> searched(Choice choice) {
        return candidates(choice, System.getProperty("os.name", ""), System.getenv(), home(), root()).stream().map(Found::path).toList();
    }

    private static Path home() {
        return Path.of(System.getProperty("user.home", ""));
    }

    /** macOS、Linux 上是 {@code /}；Windows 上是系统盘的根（{@code SystemDrive}，默认 {@code C:}），不是当前目录所在的盘。 */
    private static Path root() {
        String os = System.getProperty("os.name", "");
        if (!windows(os)) {
            return Path.of("/");
        }
        String drive = variable(System.getenv(), "SystemDrive");
        return Path.of((drive == null || drive.isBlank() ? "C:" : drive.strip()) + "\\");
    }

    /**
     * @param env Windows 上从这里取 {@code ProgramFiles}、{@code ProgramFiles(x86)}、{@code LOCALAPPDATA}（名字不分大小写）
     * @param home 家目录（macOS 上的 {@code ~/Applications}；Windows 上没有 {@code LOCALAPPDATA} 时的 {@code AppData\Local}）
     * @param root macOS、Linux 上的 {@code /}，Windows 上系统盘的根；测试里换成临时目录
     */
    static Optional<Found> find(Choice choice, String os, Map<String, String> env, Path home, Path root) {
        return candidates(choice, os, env, home, root).stream().filter(found -> usable(found.path())).findFirst();
    }

    /** 要看的位置，先 Chrome 后 Edge；指定了一个就只有那一个的。 */
    static List<Found> candidates(Choice choice, String os, Map<String, String> env, Path home, Path root) {
        List<Found> candidates = new ArrayList<>();
        if (choice != Choice.EDGE) {
            chrome(os, env, home, root).forEach(path -> candidates.add(new Found(CHROME, path)));
        }
        if (choice != Choice.CHROME) {
            edge(os, env, home, root).forEach(path -> candidates.add(new Found(EDGE, path)));
        }
        return candidates;
    }

    /**
     * 是个文件而且能执行。{@code --browser-path} 也用它查。Windows 上没有执行位，是个文件就算：那里的“能不能执行”查的是访问控制表，
     * 对 {@code Program Files} 里的程序有时答“不能”，其实照样能启动。
     */
    public static boolean usable(Path file) {
        return Files.isRegularFile(file) && (WINDOWS_FILES || Files.isExecutable(file));
    }

    private static final boolean WINDOWS_FILES = java.io.File.separatorChar == '\\';

    private static List<Path> chrome(String os, Map<String, String> env, Path home, Path root) {
        if (mac(os)) {
            String app = "Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
            return List.of(root.resolve(app), home.resolve(app));
        }
        if (windows(os)) {
            return programs(env, home, root, "Google\\Chrome\\Application\\chrome.exe");
        }
        return List.of(root.resolve("usr/bin/google-chrome"), root.resolve("usr/bin/google-chrome-stable"));
    }

    private static List<Path> edge(String os, Map<String, String> env, Path home, Path root) {
        if (mac(os)) {
            String app = "Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge";
            return List.of(root.resolve(app), home.resolve(app));
        }
        if (windows(os)) {
            return programs(env, home, root, "Microsoft\\Edge\\Application\\msedge.exe");
        }
        return List.of(root.resolve("usr/bin/microsoft-edge"), root.resolve("usr/bin/microsoft-edge-stable"));
    }

    /**
     * Windows 上程序可能装在的三处：环境变量 {@code ProgramFiles}、{@code ProgramFiles(x86)}、{@code LOCALAPPDATA} 指的地方。
     * 变量名不分大小写（Windows 本身不分，但 Java 给的环境变量表区分，而有的启动方式会把名字写成全大写）；变量没有、或写得不成路径时，
     * 用系统盘上的标准位置：{@code Program Files}、{@code Program Files (x86)} 和家目录下的 {@code AppData\Local}。
     */
    private static List<Path> programs(Map<String, String> env, Path home, Path root, String relative) {
        List<Path> bases = new ArrayList<>();
        addBase(bases, variable(env, "ProgramFiles"), root.resolve("Program Files"));
        addBase(bases, variable(env, "ProgramFiles(x86)"), root.resolve("Program Files (x86)"));
        addBase(bases, variable(env, "LOCALAPPDATA"), home.resolve("AppData").resolve("Local"));
        List<Path> paths = new ArrayList<>();
        for (Path base : bases) {
            Path file = base;
            for (String part : relative.split("\\\\")) {
                file = file.resolve(part);
            }
            if (!paths.contains(file)) {
                paths.add(file);
            }
        }
        return paths;
    }

    private static void addBase(List<Path> bases, String configured, Path standard) {
        if (configured != null && !configured.isBlank()) {
            try {
                bases.add(Path.of(configured.strip()));
                return;
            } catch (InvalidPathException unusable) {
                // 用标准位置
            }
        }
        bases.add(standard);
    }

    /** 环境变量，名字不分大小写；没有时为 null。 */
    static String variable(Map<String, String> env, String name) {
        String exact = env.get(name);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, String> entry : env.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    static boolean mac(String os) {
        return os.toLowerCase(Locale.ROOT).contains("mac");
    }

    static boolean windows(String os) {
        return os.toLowerCase(Locale.ROOT).startsWith("windows");
    }
}
