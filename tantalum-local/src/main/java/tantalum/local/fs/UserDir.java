package tantalum.local.fs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import tantalum.local.wire.DirState;

/**
 * 用户在插件页填的一个目录（技能目录、wiki 目录）在这台电脑上落在哪。绝对路径，或 {@code ~} 开头（按这台电脑的家目录展开，服务器不知道它在哪）。
 * 真实路径必须在允许的目录里。{@code path} 只在 {@link DirState#OK} 时有值，是真实路径。
 *
 * <p>和文件工具不同，这里分开说“不在范围里”和“还不存在”：目录是用户自己填的，提示给他本人看。范围外的路径不管存不存在都答
 * {@link DirState#OUTSIDE}，所以不能借它探测范围外有什么。
 */
public record UserDir(DirState state, Path path) {

    public static UserDir locate(AllowedDirs dirs, String dir) {
        return locate(dirs, dir, System.getProperty("user.home"));
    }

    static UserDir locate(AllowedDirs dirs, String dir, String home) {
        Path candidate;
        try {
            candidate = expand(dir.strip(), home);
        } catch (InvalidPathException failure) {
            return new UserDir(DirState.OUTSIDE, null);
        }
        if (candidate == null || !candidate.isAbsolute()) {
            return new UserDir(DirState.OUTSIDE, null);
        }
        candidate = candidate.normalize();
        Path real;
        try {
            real = candidate.toRealPath();
        } catch (IOException missing) {
            // 写的是经过符号链接的路径（如 macOS 的 /tmp）而目录还没建时，这里只能按字面比
            return new UserDir(dirs.contains(candidate) ? DirState.MISSING : DirState.OUTSIDE, null);
        }
        if (!dirs.contains(real)) {
            return new UserDir(DirState.OUTSIDE, null);
        }
        return Files.isDirectory(real) ? new UserDir(DirState.OK, real) : new UserDir(DirState.MISSING, null);
    }

    private static Path expand(String dir, String home) {
        if (dir.isEmpty()) {
            return null;
        }
        if (dir.equals("~")) {
            return Path.of(home);
        }
        if (dir.startsWith("~/") || dir.startsWith("~\\")) {
            return Path.of(home, dir.substring(2));
        }
        return Path.of(dir);
    }
}
