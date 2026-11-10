package tantalum.shell.project;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 工作区路径是用户电脑上的路径，服务器上不一定存在，所以只按文字处理。规则和控制台的 {@code pathKey.ts} 一模一样，
 * 两边用同一张用例表（测试资源 {@code project/paths.json}）。
 */
public final class ProjectPaths {

    private static final Pattern DRIVE = Pattern.compile("^[A-Za-z]:[\\\\/].*");

    private ProjectPaths() {}

    /** Unix 的 {@code /…}，或 Windows 的 {@code X:\…}、{@code X:/…}。网络路径、{@code ~}、相对路径都不算。 */
    public static boolean isAbsolute(String path) {
        String trimmed = path.strip();
        return trimmed.startsWith("/") || DRIVE.matcher(trimmed).matches();
    }

    /** 判断两个路径是不是同一个目录用的键：去首尾空白和末尾分隔符；Windows 路径统一成 {@code /} 并转小写。 */
    public static String key(String path) {
        String trimmed = path.strip();
        if (DRIVE.matcher(trimmed).matches()) {
            String unified = trimmed.replace('\\', '/').toLowerCase(Locale.ROOT);
            return stripTrailing(unified, 3);
        }
        return stripTrailing(trimmed, 1);
    }

    /** 默认名字：最后一段。根目录没有最后一段，用路径本身。 */
    public static String name(String path) {
        String trimmed = path.strip();
        boolean windows = DRIVE.matcher(trimmed).matches();
        String stripped = stripTrailing(windows ? trimmed.replace('\\', '/') : trimmed, windows ? 3 : 1);
        int slash = stripped.lastIndexOf('/');
        String last = stripped.substring(slash + 1);
        return last.isEmpty() || (windows && stripped.length() <= 3) ? trimmed : last;
    }

    /** {@code dir} 是不是 {@code root} 本身或它下面的目录（按 {@link #key} 比较，只看文字）。 */
    public static boolean within(String dir, String root) {
        String inner = key(dir);
        String outer = key(root);
        return inner.equals(outer) || inner.startsWith(outer.endsWith("/") ? outer : outer + "/");
    }

    /** 去掉末尾的分隔符，但至少留 {@code keep} 个字符（{@code /} 或 {@code c:/}）。 */
    private static String stripTrailing(String path, int keep) {
        int end = path.length();
        while (end > keep && (path.charAt(end - 1) == '/' || path.charAt(end - 1) == '\\')) {
            end--;
        }
        return path.substring(0, end);
    }
}
