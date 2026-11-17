package tantalum.local.tool;

import java.util.Objects;
import tantalum.local.fs.AllowedDirs;
import tantalum.local.shell.Shell;
import tantalum.local.shell.Unavailable;

/**
 * 一次调用的上下文：哪个会话、能进哪些目录、先读后写的记录、跑命令的 shell、用户填的技能目录（只有 {@code skill} 工具用，别的调用为 null）、这台电脑上的浏览器（没允许时是一律拒绝的那个）。连接程序只服务一个用户，用户由它在调用前核对，所以这里只有会话。
 */
public record LocalContext(String session, AllowedDirs dirs, Observations observations, Shell shell, String skills,
        Browser browser) {

    /** 不能跑命令时（没有 {@code --allow-shell}、测试）。 */
    static final Shell NO_SHELL = Shell.unavailable(Unavailable.of(Unavailable.Code.SHELL_NOT_ALLOWED));

    public LocalContext {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(dirs, "dirs");
        Objects.requireNonNull(observations, "observations");
        Objects.requireNonNull(shell, "shell");
        browser = browser == null ? Browser.NOT_ALLOWED : browser;
    }

    /** 同一份上下文，带上这次调用的技能目录。 */
    public LocalContext withSkills(String dir) {
        return new LocalContext(session, dirs, observations, shell, dir, browser);
    }

    /** 同一份上下文，带上这台电脑上的浏览器。 */
    public LocalContext withBrowser(Browser browser) {
        return new LocalContext(session, dirs, observations, shell, skills, browser);
    }
}
