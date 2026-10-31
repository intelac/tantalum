package tantalum.shell.tool;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import tantalum.shell.plugin.Workspace;
import tantalum.value.id.Principal;

/**
 * 按用户给出工作目录。默认每人一个子目录，也可以配置为共用。
 * 这只是路径校验，不是沙箱：工具仍在同一进程和系统账号下运行。
 */
public final class Workspaces {

    private static final Pattern SAFE_NAME = Pattern.compile("[a-z0-9_-]{1,64}");

    private final Path root;
    private final boolean shared;
    private final Map<Principal, Workspace> byPrincipal = new ConcurrentHashMap<>();

    private Workspaces(Path root, boolean shared) {
        this.root = root;
        this.shared = shared;
    }

    public static Workspaces perUser(Path root) {
        return new Workspaces(root, false);
    }

    public static Workspaces shared(Path root) {
        return new Workspaces(root, true);
    }

    /** 用户名可以安全地用作目录名：不含路径分隔符，也不可能是 {@code ..}。 */
    public static boolean isSafeName(String name) {
        return name != null && SAFE_NAME.matcher(name).matches();
    }

    public boolean isShared() {
        return shared;
    }

    /** 目录不存在时创建，权限 700。名字不安全时抛 {@link IllegalArgumentException}。 */
    public Workspace of(Principal principal) {
        return byPrincipal.computeIfAbsent(principal, this::open);
    }

    private Workspace open(Principal principal) {
        try {
            if (shared) {
                Files.createDirectories(root);
                return new Workspace(root);
            }
            if (!isSafeName(principal.value())) {
                throw new IllegalArgumentException("user name cannot be used as a directory: " + principal.value());
            }
            Path dir = root.resolve(principal.value());
            Files.createDirectories(dir);
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
            return new Workspace(dir);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
