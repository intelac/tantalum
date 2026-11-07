package tantalum.local.fs;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * 只有本人能读的文件和目录：创建、收紧、替换、目录落盘。所有 POSIX 权限代码都经过这里，
 * {@code ArchitectureTest} 拦住绕过它的代码。
 *
 * <p>文件系统支持 POSIX 权限时（macOS、Linux）和原来的写法逐个系统调用一样：新文件 600、新目录 700，已有的收紧。
 * 不支持时（Windows 的 NTFS）照常创建、不带属性，新文件继承所在目录的 ACL；不读也不写 ACL。照 pi
 * {@code CA/src/core/auth-storage.ts:36-37,71}（Node 在 Windows 上忽略 mode）和 DSH
 * {@code credentials/credentials-local/src/index.ts:121-123,137}。
 */
public final class PrivateFiles {

    /** 目录落盘和替换重试是平台行为，不是文件系统属性，所以只有这两处按系统判断。 */
    static final boolean WINDOWS = System.getProperty("os.name", "").startsWith("Windows");
    /** 替换被占用时最多等这么久。 */
    static final long REPLACE_BUDGET_MILLIS = 1000;

    private static final Set<PosixFilePermission> FILE_MODE = PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> DIR_MODE = PosixFilePermissions.fromString("rwx------");
    private static final FileAttribute<?>[] NONE = new FileAttribute<?>[0];

    private PrivateFiles() {}

    /** 改名，测试换成会失败的。 */
    @FunctionalInterface
    interface Mover {
        void move(Path source, Path target) throws IOException;
    }

    /** 等待，测试换成不真睡的。 */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    /** 这个路径所在的文件系统支持 POSIX 权限。只看文件系统支持哪些属性视图，不访问磁盘。 */
    public static boolean posix(Path path) {
        return path.getFileSystem().supportedFileAttributeViews().contains("posix");
    }

    /** 建文件、打开通道时带的属性：POSIX 上是 600，否则没有。 */
    public static FileAttribute<?>[] fileAttributes(Path path) {
        return posix(path) ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(FILE_MODE)} : NONE;
    }

    /** 同 {@link Files#createFile}，POSIX 上是 600。 */
    public static void createFile(Path file) throws IOException {
        Files.createFile(file, fileAttributes(file));
    }

    /** 同 {@link Files#createDirectories}：POSIX 上新建的每一级都是 700，已有的上级不碰。 */
    public static void createDirectories(Path dir) throws IOException {
        if (posix(dir)) {
            Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(DIR_MODE));
        } else {
            Files.createDirectories(dir);
        }
    }

    /** 同 {@link Files#createTempFile(Path, String, String, FileAttribute[])}，POSIX 上是 600。 */
    public static Path createTempFile(Path dir, String prefix, String suffix) throws IOException {
        return Files.createTempFile(dir, prefix, suffix, fileAttributes(dir));
    }

    /** 已有的文件收紧成 600；非 POSIX 不做。 */
    public static void restrict(Path file) throws IOException {
        if (posix(file)) {
            Files.setPosixFilePermissions(file, FILE_MODE);
        }
    }

    /** 已有的目录收紧成 700；非 POSIX 不做。 */
    public static void restrictDirectory(Path dir) throws IOException {
        if (posix(dir)) {
            Files.setPosixFilePermissions(dir, DIR_MODE);
        }
    }

    /**
     * 把 {@code from} 的权限给 {@code to}（不跟随符号链接读），替换用户的文件时保留原来的权限；非 POSIX 不做。
     * Windows 上替换后的文件继承目录的 ACL，原文件单独设的 ACL 会丢，归 Windows 连接程序（DSH 用 {@code ReplaceFileW}，
     * {@code fs/fs-local/src/win32.ts:103-122}）。
     */
    public static void copyPermissions(Path from, Path to) throws IOException {
        if (posix(from)) {
            Files.setPosixFilePermissions(to, Files.getPosixFilePermissions(from, LinkOption.NOFOLLOW_LINKS));
        }
    }

    /**
     * 原子地用 {@code source} 替换 {@code target}（{@code ATOMIC_MOVE}；Windows 上是 {@code MoveFileEx} 带
     * {@code MOVEFILE_REPLACE_EXISTING}）。Windows 上目标被杀毒软件、索引服务这类句柄短暂占用时，在 1 秒内重试，
     * 还不行就抛最后一次的异常；POSIX 上不重试。失败时 {@code source} 还在，由调用方删。
     */
    public static void replace(Path source, Path target) throws IOException {
        replace(source, target, PrivateFiles::atomicMove, WINDOWS, Thread::sleep);
    }

    static void replace(Path source, Path target, Mover mover, boolean windows, Sleeper sleeper) throws IOException {
        long waited = 0;
        long delay = 10;
        while (true) {
            try {
                mover.move(source, target);
                return;
            } catch (FileSystemException failure) {
                if (!windows || !busy(failure) || waited >= REPLACE_BUDGET_MILLIS) {
                    throw failure;
                }
                long wait = Math.min(delay, REPLACE_BUDGET_MILLIS - waited);
                try {
                    sleeper.sleep(wait);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw failure;
                }
                waited += wait;
                delay = Math.min(delay * 2, 200);
            }
        }
    }

    /**
     * 目标被占用：JDK 对拒绝访问抛 {@link AccessDeniedException}，对共享冲突抛的正好是 {@link FileSystemException}
     * 本身，消息是本地化的，所以按类判断。{@code NoSuchFileException} 这类子类重试也不会好。
     */
    private static boolean busy(FileSystemException failure) {
        return failure instanceof AccessDeniedException || failure.getClass() == FileSystemException.class;
    }

    private static void atomicMove(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * 目录项落盘：新建、改名的文件崩溃后还在。Windows 上跳过：不能只读打开目录（{@code AccessDeniedException}），
     * NTFS 的元数据有日志，进程崩溃不受影响，断电可能丢最新的目录项（照 DSH {@code storage/storage-json/src/atomic.ts:42-56}）。
     */
    public static void syncDirectory(Path dir) throws IOException {
        if (WINDOWS) {
            return;
        }
        try (FileChannel channel = FileChannel.open(dir, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }
}
