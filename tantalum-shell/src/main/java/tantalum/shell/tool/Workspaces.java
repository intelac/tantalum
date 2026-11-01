package tantalum.shell.tool;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Objects;
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

    /** 每人模式下目录不存在时创建，权限 700（共用模式只建目录）。名字不安全时抛 {@link IllegalArgumentException}。 */
    public Workspace of(Principal principal) {
        Objects.requireNonNull(principal, "principal");
        Workspace existing = byPrincipal.get(principal);
        if (existing != null) {
            return existing;
        }
        // 文件 I/O 放在 compute 之外，不占 ConcurrentHashMap 的桶锁；重复打开只是多算一次路径。
        Workspace opened = open(principal);
        Workspace raced = byPrincipal.putIfAbsent(principal, opened);
        return raced == null ? opened : raced;
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
            restrictToOwner(dir);
            Workspace workspace = new Workspace(dir);
            // 用户目录若被换成指向外面的符号链接，真实路径就不在 root 下，不能把外面的目录当成它的工作区。
            if (!workspace.root().startsWith(root.toRealPath())) {
                throw new IllegalStateException("workspace for " + principal.value() + " points outside " + root);
            }
            return workspace;
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static void restrictToOwner(Path dir) throws IOException {
        try {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException notPosix) {
            // Windows 等非 POSIX 文件系统：权限靠操作系统的默认 ACL。
        }
    }
}
