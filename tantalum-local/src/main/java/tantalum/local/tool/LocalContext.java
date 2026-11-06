package tantalum.local.tool;

import java.util.Objects;
import tantalum.local.fs.AllowedDirs;

/**
 * 一次调用的上下文：哪个会话、能进哪些目录、先读后写的记录。连接程序只服务一个用户，用户由它在调用前核对，所以这里只有会话。
 */
public record LocalContext(String session, AllowedDirs dirs, Observations observations) {

    public LocalContext {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(dirs, "dirs");
        Objects.requireNonNull(observations, "observations");
    }
}
