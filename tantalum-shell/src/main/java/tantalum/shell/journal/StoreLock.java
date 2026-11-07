package tantalum.shell.journal;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import tantalum.local.fs.PrivateFiles;

/** 进程独占锁：一个进程拥有一份存储。进程退出时由操作系统释放。 */
public final class StoreLock implements AutoCloseable {

    private final FileChannel channel;
    private final FileLock lock;

    private StoreLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    public static StoreLock acquire(Path dir) throws IOException {
        Files.createDirectories(dir);
        // 和数据目录里的其他文件一样只有本人可读写；已存在的锁文件也收紧。
        Path file = dir.resolve("store.lock");
        FileChannel channel = FileChannel.open(file, Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE),
                PrivateFiles.fileAttributes(file));
        FileLock lock;
        try {
            PrivateFiles.restrict(file);
            lock = channel.tryLock();
        } catch (OverlappingFileLockException held) {
            lock = null;
        } catch (IOException | RuntimeException failure) {
            // 加锁前的任何失败都不能漏掉通道
            channel.close();
            throw failure;
        }
        if (lock == null) {
            channel.close();
            throw new IllegalStateException("store is locked by another process: " + dir);
        }
        return new StoreLock(channel, lock);
    }

    /** 可重复调用。 */
    @Override
    public void close() throws IOException {
        if (!channel.isOpen()) {
            return;
        }
        try (channel) {
            if (lock.isValid()) {
                lock.release();
            }
        }
    }
}
