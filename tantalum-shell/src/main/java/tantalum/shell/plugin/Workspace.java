package tantalum.shell.plugin;

import java.io.IOException;
import java.nio.file.Path;

/** 校验路径，拒绝越出工作目录，包括 {@code ..} 和符号链接。 */
public final class Workspace {

    private final Path root;

    public Workspace(Path root) throws IOException {
        this.root = root.toRealPath();
    }

    /** 返回真实路径。路径不存在或越界时抛 {@link IllegalArgumentException}。 */
    public Path resolve(String path) {
        Path real;
        try {
            real = root.resolve(path).toRealPath();
        } catch (IOException failure) {
            throw new IllegalArgumentException("path not found: " + path, failure);
        }
        if (!real.startsWith(root)) {
            throw new IllegalArgumentException("path is outside the workspace: " + path);
        }
        return real;
    }

    public Path root() {
        return root;
    }
}
