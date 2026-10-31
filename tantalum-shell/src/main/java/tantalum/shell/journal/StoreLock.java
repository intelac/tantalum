package tantalum.shell.journal;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

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
        FileChannel channel = FileChannel.open(dir.resolve("store.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (OverlappingFileLockException held) {
            lock = null;
        }
        if (lock == null) {
            channel.close();
            throw new IllegalStateException("store is locked by another process: " + dir);
        }
        return new StoreLock(channel, lock);
    }

    @Override
    public void close() throws IOException {
        lock.release();
        channel.close();
    }
}
