package tantalum.shell.boot;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclFileAttributeView;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.shell.cli.CliText;

/**
 * 启动时检查数据目录的位置和卷。只在 Windows 上查：网络共享拒绝启动（SQLite 的 WAL 不能用在网络文件系统上）；
 * 不在用户目录下、卷不支持 ACL 时各 WARN 一行，照常启动。按位置判断，不读 ACL：JDK 给的 ACL 条目只有本地化的账号名，拿不到 SID。
 * macOS、Linux 上什么都不做，权限照旧由 {@code PrivateFiles} 给 600/700。pi、DSH 都没有对应做法，自定。
 */
final class DataDirCheck {

    /** 三种情况。服务器日志用 {@link Finding#english()}，命令行按 {@code CliText} 的语言说同样的话。 */
    enum Problem { NETWORK_SHARE, OUTSIDE_PROFILE, NO_ACL }

    /** {@code command} 只有 {@link Problem#OUTSIDE_PROFILE} 有：只给本人、SYSTEM、Administrators 的 {@code icacls} 命令。 */
    record Finding(Problem problem, String dir, String home, String command) {

        /** 占位符：{@code dir}、{@code home}、{@code command}。 */
        Map<String, String> params() {
            return Map.of("dir", dir, "home", home, "command", command);
        }

        String english() {
            return switch (problem) {
                case NETWORK_SHARE -> "The data directory " + dir + " is on a network share. SQLite cannot keep the index consistent there;"
                        + " use a local disk.";
                case OUTSIDE_PROFILE -> "The data directory " + dir + " is outside your user profile (" + home + "), so other users of this"
                        + " computer may be able to read it. To limit it to you, SYSTEM and Administrators, run:\n  " + command;
                case NO_ACL -> "The data directory " + dir + " is on a volume without file permissions (FAT32 or exFAT), so anyone using"
                        + " this computer can read it. Use an NTFS volume.";
            };
        }
    }

    /** {@code refusal} 不为空时拒绝启动。 */
    record Result(Optional<Finding> refusal, List<Finding> warnings) {}

    private static final Logger LOG = LoggerFactory.getLogger(DataDirCheck.class);
    private static final boolean WINDOWS = System.getProperty("os.name", "").startsWith("Windows");

    private DataDirCheck() {}

    /** 服务器启动：WARN 写日志（英文，同其他日志）；拒绝时抛 {@link IllegalStateException}，Spring 启动失败并打出这句话。 */
    static void atStartup(Path dataDir) {
        Result result = check(dataDir);
        for (Finding warning : result.warnings()) {
            LOG.warn("{}", warning.english());
        }
        if (result.refusal().isPresent()) {
            throw new IllegalStateException(result.refusal().get().english());
        }
    }

    /** 子命令 {@code users}、{@code check}、{@code browser}：按 {@link CliText} 的语言打印；拒绝时返回 false，调用方退出码 1。 */
    static boolean forCommand(Path dataDir, PrintStream out, CliText text) {
        return print(check(dataDir), out, text);
    }

    static boolean print(Result result, PrintStream out, CliText text) {
        for (Finding warning : result.warnings()) {
            out.println(text.say(key(warning.problem()), warning.params()));
        }
        result.refusal().ifPresent(refusal -> out.println(text.say(key(refusal.problem()), refusal.params())));
        return result.refusal().isEmpty();
    }

    private static CliText.Key key(Problem problem) {
        return switch (problem) {
            case NETWORK_SHARE -> CliText.Key.DATA_DIR_NETWORK_SHARE;
            case OUTSIDE_PROFILE -> CliText.Key.DATA_DIR_OUTSIDE_PROFILE;
            case NO_ACL -> CliText.Key.DATA_DIR_NO_ACL;
        };
    }

    /** 只在 Windows 上检查；macOS、Linux 返回空结果。网络路径只解析、不访问。 */
    static Result check(Path dataDir) {
        if (!WINDOWS) {
            return new Result(Optional.empty(), List.of());
        }
        Path absolute = dataDir.toAbsolutePath().normalize();
        String home = System.getProperty("user.home", "");
        String user = System.getProperty("user.name", "");
        if (networkPath(absolute)) {
            return check(absolute.toString(), home, user, true, true, false, false);
        }
        Path existing = nearestExisting(absolute);
        return check(absolute.toString(), home, user, true, false, insideHome(existing, Path.of(home)), aclSupported(existing));
    }

    /** 测试用：把要查的事实传进来。 */
    static Result check(String dataDir, String home, String user, boolean windows, boolean networkPath,
                        boolean insideHome, boolean aclSupported) {
        if (!windows) {
            return new Result(Optional.empty(), List.of());
        }
        if (networkPath) {
            return new Result(Optional.of(new Finding(Problem.NETWORK_SHARE, dataDir, home, "")), List.of());
        }
        List<Finding> warnings = new ArrayList<>();
        if (!insideHome) {
            warnings.add(new Finding(Problem.OUTSIDE_PROFILE, dataDir, home, icacls(dataDir, user)));
        }
        if (!aclSupported) {
            warnings.add(new Finding(Problem.NO_ACL, dataDir, home, ""));
        }
        return new Result(Optional.empty(), List.copyOf(warnings));
    }

    /**
     * 用 SID 写组名，不受系统语言影响：{@code *S-1-5-18} 是 SYSTEM，{@code *S-1-5-32-544} 是 Administrators。
     * {@code /inheritance:r} 去掉从上级继承来的“已验证的用户”这类条目。
     */
    static String icacls(String dir, String user) {
        return "icacls \"" + dir + "\" /inheritance:r /grant:r \"" + user + ":(OI)(CI)F\" \"*S-1-5-18:(OI)(CI)F\""
                + " \"*S-1-5-32-544:(OI)(CI)F\"";
    }

    /** 根是 {@code \\server\share\}（包括 {@code \\?\UNC\…}）。映射成盘符的网络驱动器认不出来，README 写明只能用本地磁盘。 */
    static boolean networkPath(Path absolute) {
        Path root = absolute.getRoot();
        return root != null && root.toString().startsWith("\\\\");
    }

    private static Path nearestExisting(Path absolute) {
        Path existing = absolute;
        while (existing.getParent() != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        return existing;
    }

    /** 两边都取真实路径：展开 8.3 短名（runner 的 {@code RUNNER~1}）、改成磁盘上的大小写；Windows 上 {@code startsWith} 不分大小写。 */
    private static boolean insideHome(Path existing, Path home) {
        try {
            return existing.toRealPath().startsWith(home.toRealPath());
        } catch (IOException unreadable) {
            return false;
        }
    }

    private static boolean aclSupported(Path existing) {
        try {
            return Files.getFileStore(existing).supportsFileAttributeView(AclFileAttributeView.class);
        } catch (IOException unreadable) {
            return true;
        }
    }
}
