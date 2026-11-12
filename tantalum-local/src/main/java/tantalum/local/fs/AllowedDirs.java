package tantalum.local.fs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 用户在连接程序上用 {@code --allow} 给的几个目录，第一个是主目录。规则同服务器上原来的
 * {@code Workspace}，逐条保留：相对路径按主目录解析；解析后（包括 {@code ..} 和符号链接）必须落在某个允许的目录里；
 * 越界和不存在用同一句话。这是路径校验不是沙箱：校验和随后的打开之间目录可能变化。
 * 会话有工作目录时用 {@link #from} 换起点：相对路径从会话目录算，允许的范围不变。
 */
public final class AllowedDirs {

    /** 越界和不存在用同一句话，否则调用方能借不同的错误探测目录外的文件是否存在。 */
    public static final String NOT_FOUND = "path not found in the allowed directories: ";

    /** 真实路径，去掉重复，第一个是主目录。 */
    private final List<Path> dirs;
    /** 相对路径从这里算：主目录，或用 {@link #from} 换成的会话目录。 */
    private final Path start;

    /** 目录必须已存在。一个都没有、不存在或不是目录时抛 {@link IllegalArgumentException}。 */
    public AllowedDirs(List<Path> dirs) throws IOException {
        if (dirs.isEmpty()) {
            throw new IllegalArgumentException("no allowed directory");
        }
        List<Path> real = new ArrayList<>();
        for (Path dir : dirs) {
            Path resolved = dir.toRealPath();
            if (!Files.isDirectory(resolved)) {
                throw new IllegalArgumentException("not a directory: " + dir);
            }
            if (!real.contains(resolved)) {
                real.add(resolved);
            }
        }
        this.dirs = List.copyOf(real);
        this.start = this.dirs.getFirst();
    }

    private AllowedDirs(List<Path> dirs, Path start) {
        this.dirs = dirs;
        this.start = start;
    }

    /**
     * 起点换成 {@code dir} 的同一组允许目录。{@code dir} 必须存在、是目录、真实路径在某个允许的目录里（同 {@link #resolve}），
     * 否则抛 {@link IllegalArgumentException}，措辞同 {@link #resolve}。
     */
    public AllowedDirs from(String dir) {
        Path real = resolve(dir);
        if (!Files.isDirectory(real)) {
            throw new IllegalArgumentException(NOT_FOUND + dir);
        }
        return new AllowedDirs(dirs, real);
    }

    /** 相对路径找不到时补的一句：从哪算起、下一步怎么找。不含任何真实路径。 */
    public static final String RELATIVE_HINT = " (relative paths start at the working directory; use find_files to locate a file by its name)";

    /**
     * 找不到或越界时的那句话。相对路径后面补一句提示：真模型常把 {@code inventory/pricing.py} 写成 {@code pricing.py}，
     * 或者凭印象猜一个目录（tool-scenarios 第一轮，两个模型共 11 次）。提示是固定的一句，不带真实路径；
     * 存在和越界仍然是同一句话，探测不出目录外有什么。
     */
    String notFound(String path) {
        boolean relative;
        try {
            relative = !Path.of(path).isAbsolute();
        } catch (java.nio.file.InvalidPathException malformed) {
            relative = false;
        }
        return relative ? NOT_FOUND + path + RELATIVE_HINT : NOT_FOUND + path;
    }

    /** 相对路径的起点：主目录，或会话目录。 */
    public Path main() {
        return start;
    }

    public List<Path> all() {
        return dirs;
    }

    /** 返回真实路径。路径不存在或越界时抛 {@link IllegalArgumentException}。 */
    public Path resolve(String path) {
        Path candidate = main().resolve(path).normalize();
        if (!inside(candidate)) {
            throw new IllegalArgumentException(notFound(path));
        }
        Path real;
        try {
            real = candidate.toRealPath();
        } catch (IOException failure) {
            throw new IllegalArgumentException(notFound(path));
        }
        if (!inside(real)) {
            throw new IllegalArgumentException(notFound(path));
        }
        return real;
    }

    /**
     * 写入用：目标可以还不存在。已存在时同 {@link #resolve}；不存在时校验最近一个已存在的上级目录的真实路径在某个允许的目录里，
     * 返回它加上还不存在的那几段。允许的目录本身、断开的符号链接、越界都抛 {@link IllegalArgumentException}，措辞同 {@link #resolve}。
     */
    public Path resolveForWrite(String path) {
        Path candidate = main().resolve(path).normalize();
        if (!inside(candidate) || dirs.contains(candidate)) {
            throw new IllegalArgumentException(notFound(path));
        }
        if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            return resolve(path);
        }
        Path existing = candidate.getParent();
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            throw new IllegalArgumentException(notFound(path));
        }
        Path real;
        try {
            real = existing.toRealPath();
        } catch (IOException failure) {
            throw new IllegalArgumentException(notFound(path));
        }
        if (!inside(real) || !Files.isDirectory(real)) {
            throw new IllegalArgumentException(notFound(path));
        }
        return real.resolve(existing.relativize(candidate));
    }

    /** 给模型看的路径：主目录里的写相对主目录的路径，其他允许目录里的写绝对路径。两种都能原样交回给工具。 */
    public String display(Path real) {
        if (!real.startsWith(main())) {
            return real.toString();
        }
        String relative = main().relativize(real).toString();
        return relative.isEmpty() ? "." : relative;
    }

    /** {@code path}（已经是绝对路径）是不是在某个允许的目录里。只比路径，不解析符号链接。 */
    public boolean contains(Path path) {
        return inside(path);
    }

    private boolean inside(Path path) {
        for (Path dir : dirs) {
            if (path.startsWith(dir)) {
                return true;
            }
        }
        return false;
    }
}
