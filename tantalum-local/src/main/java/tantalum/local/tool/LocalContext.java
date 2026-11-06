package tantalum.local.tool;

import java.util.Objects;
import tantalum.local.fs.AllowedDirs;
import tantalum.local.sandbox.Sandbox;

/**
 * 一次调用的上下文：哪个会话、能进哪些目录、先读后写的记录、跑命令用的沙箱。连接程序只服务一个用户，用户由它在调用前核对，所以这里只有会话。
 */
public record LocalContext(String session, AllowedDirs dirs, Observations observations, Sandbox sandbox) {

    /** 没有命令沙箱时（没有 {@code --allow-shell}、测试）。 */
    static final Sandbox NO_SANDBOX = Sandbox.unavailable("--allow-shell was not given");

    public LocalContext {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(dirs, "dirs");
        Objects.requireNonNull(observations, "observations");
        Objects.requireNonNull(sandbox, "sandbox");
    }

    /** 不跑命令的调用：文件和 Git 工具用不到沙箱。 */
    public LocalContext(String session, AllowedDirs dirs, Observations observations) {
        this(session, dirs, observations, NO_SANDBOX);
    }
}
