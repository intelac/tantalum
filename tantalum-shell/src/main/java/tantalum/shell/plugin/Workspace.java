package tantalum.shell.plugin;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 校验路径，拒绝越出工作目录，包括 {@code ..} 和符号链接。
 * 这是路径校验不是沙箱：校验和随后的打开之间目录可能变化，真正的隔离留给 sandbox。
 */
public final class Workspace {

    /** 越界和不存在用同一句话，否则调用方能借不同的错误探测目录外的文件是否存在。 */
    static final String NOT_FOUND = "path not found in the workspace: ";

    private final Path root;

    public Workspace(Path root) throws IOException {
        this.root = root.toRealPath();
    }

    /** 返回真实路径。路径不存在或越界时抛 {@link IllegalArgumentException}。 */
    public Path resolve(String path) {
        Path candidate = root.resolve(path).normalize();
        if (!candidate.startsWith(root)) {
            throw new IllegalArgumentException(NOT_FOUND + path);
        }
        Path real;
        try {
            real = candidate.toRealPath();
        } catch (IOException failure) {
            throw new IllegalArgumentException(NOT_FOUND + path);
        }
        if (!real.startsWith(root)) {
            throw new IllegalArgumentException(NOT_FOUND + path);
        }
        return real;
    }

    public Path root() {
        return root;
    }
}
