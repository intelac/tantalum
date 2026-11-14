package tantalum.local.shell;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import tantalum.local.fs.PrivateFiles;

/**
 * 放命令完整输出的目录（tool-scenarios-2 需求 6.2）。照 DSH 笔记 {@code 2026-07-08-tool-output-spill-files}：输出太长时完整的
 * stdout/stderr 写进一个只有本人能读的临时文件，结果里写明路径，模型用 {@code read_file}、{@code grep} 按需去读。
 * 一个连接程序一个目录，连接程序退出时删掉（{@link #close}）。
 */
public final class Spill implements AutoCloseable {

    private final Path root;
    private final AtomicInteger next = new AtomicInteger();

    /** 用 {@code root}（可以还不存在，第一次写文件时建）。 */
    public Spill(Path root) {
        this.root = root;
    }

    /** 系统临时目录下新建一个只有本人能进的目录。 */
    public static Spill temporary() throws IOException {
        Path dir = Files.createTempDirectory("tantalum-output-");
        PrivateFiles.restrictDirectory(dir);
        return new Spill(dir);
    }

    public Path root() {
        return root;
    }

    /**
     * 新建一个空文件并返回路径：目录不存在时先建（POSIX 上 700），文件独占新建、POSIX 上 600（照 DSH {@code dsh-spill-local} 用
     * {@code open(path, 'wx', 0o600)}：已经有同名文件或预先放好的符号链接时建不成，写不到别处）。
     */
    Path newFile() throws IOException {
        PrivateFiles.createDirectories(root);
        Path file = root.resolve("command-output-" + next.incrementAndGet() + ".log");
        PrivateFiles.createFile(file);
        return file;
    }

    /** 删掉目录和里面的文件；删不掉的不管（进程退出时尽力而为）。 */
    @Override
    public void close() {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 文件还被别的程序占着（Windows）：留着
                }
            });
        } catch (IOException ignored) {
            // 目录已经没了
        }
    }
}
