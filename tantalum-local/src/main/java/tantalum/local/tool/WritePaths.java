package tantalum.local.tool;

import java.nio.file.Path;
import tantalum.local.fs.AllowedDirs;

/**
 * 写工具的路径规则：在某个允许的目录里（{@link AllowedDirs#resolveForWrite}）。服务器上原来还禁止写 {@code memory/}，那是服务器上
 * 记忆目录的规则；记忆留在服务器（L6），用户电脑上的 {@code memory/} 只是普通目录，这里不再拦。
 */
final class WritePaths {

    private WritePaths() {}

    /** 返回要写的真实路径；不能写时抛 {@link IllegalArgumentException}，说明原因。 */
    static Path resolve(AllowedDirs dirs, String path) {
        return dirs.resolveForWrite(path);
    }

    /** 先读后写不满足时给模型的说明（措辞照 DSH {@code tool-fs/src/error.ts:29-42}），null 表示可以改。 */
    static String refusal(Observations.Check check, String path) {
        return switch (check) {
            case OK -> null;
            case NOT_READ -> "cannot modify \"" + path + "\": file has not been read in this session — read it with read_file, then retry. "
                    + "(Reads are not remembered after the connector on the user's computer restarts.)";
            case CHANGED -> "cannot modify \"" + path + "\": file changed since it was read — read it again, then retry";
        };
    }
}
