package tantalum.shell.tool;

import java.nio.file.Path;
import tantalum.shell.plugin.Workspace;

/** 写工具的路径规则（需求 C11）：在本人工作目录里，且不在 {@code memory/} 下（记忆只能用记忆工具改）。 */
final class WritePaths {

    /** 和 {@code MemoryStores} 里每人模式的记忆目录同名。 */
    static final String MEMORY_DIR = "memory";

    private WritePaths() {}

    /** 返回要写的真实路径；不能写时抛 {@link IllegalArgumentException}，说明原因。 */
    static Path resolve(Workspace workspace, String path) {
        Path target = workspace.resolveForWrite(path);
        Path relative = workspace.root().relativize(target);
        // 不区分大小写：macOS 默认的文件系统上 Memory/ 和 memory/ 是同一个目录
        if (relative.getNameCount() > 0 && relative.getName(0).toString().equalsIgnoreCase(MEMORY_DIR)) {
            throw new IllegalArgumentException("cannot write \"" + path + "\": memory/ is managed by the memory tools — use memory_write");
        }
        return target;
    }

    /** 先读后写不满足时给模型的说明（措辞照 DSH {@code tool-fs/src/error.ts:29-42}），null 表示可以改。 */
    static String refusal(Observations.Check check, String path) {
        return switch (check) {
            case OK -> null;
            case NOT_READ -> "cannot modify \"" + path + "\": file has not been read in this session — read it with read_file, then retry. "
                    + "(Reads are not remembered across server restarts.)";
            case CHANGED -> "cannot modify \"" + path + "\": file changed since it was read — read it again, then retry";
        };
    }
}
