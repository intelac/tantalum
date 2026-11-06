package tantalum.local.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 先读后写（需求 C5，照 DSH 的观察记录 {@code tool-fs/src/error.ts:29-42}）：按会话记下每个文件最后一次读到或写下时的版本。
 * 覆盖或修改已有文件前，这个会话必须读过它，而且之后没被改过。记录只在连接程序的内存里：连接程序重启后要重新读。
 * 一个连接程序只服务一个用户，所以只按会话分。
 */
public final class Observations {

    /** 文件锁分成固定的几份，按路径散列；不同文件偶尔共用一把锁只是多等一会儿，内存不随文件数增长。 */
    private static final int LOCK_STRIPES = 64;

    /** 文件的版本：大小、修改时间、文件键（inode）。原子写会换 inode，所以改名替换也认得出来。 */
    record Version(long size, FileTime modified, Object fileKey) {

        static Version of(Path file) throws IOException {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return new Version(attributes.size(), attributes.lastModifiedTime(), attributes.fileKey());
        }
    }

    /** 检查结果。 */
    enum Check { OK, NOT_READ, CHANGED }

    @FunctionalInterface
    interface Locked<T> {
        T run() throws IOException;
    }

    private final Map<String, Map<Path, Version>> seen = new ConcurrentHashMap<>();
    private final ReentrantLock[] locks = new ReentrantLock[LOCK_STRIPES];

    public Observations() {
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    /** 写、改成功之后（仍持有文件锁时）调用，记下现在的版本。{@code file} 是真实路径。 */
    void record(String session, Path file) throws IOException {
        record(session, file, Version.of(file));
    }

    /** 读用：读之前取的版本。调用方确认读完时版本没变才记，否则模型看到的内容和记下的版本对不上。 */
    void record(String session, Path file, Version version) {
        seen.computeIfAbsent(session, ignored -> new ConcurrentHashMap<>()).put(file, version);
    }

    /** 已有文件能不能改。不存在的文件不用检查。 */
    Check check(String session, Path file) throws IOException {
        Map<Path, Version> files = seen.get(session);
        Version known = files == null ? null : files.get(file);
        if (known == null) {
            return Check.NOT_READ;
        }
        return known.equals(Version.of(file)) ? Check.OK : Check.CHANGED;
    }

    /** 同一个文件的“检查版本、写入、记下新版本”在进程内互斥，两个会话同时改只有一个成功（C8）。 */
    <T> T locked(Path file, Locked<T> action) throws IOException {
        ReentrantLock lock = locks[Math.floorMod(file.hashCode(), LOCK_STRIPES)];
        lock.lock();
        try {
            return action.run();
        } finally {
            lock.unlock();
        }
    }

    /** 会话不再需要记录时调用。 */
    public void forget(String session) {
        seen.remove(session);
    }
}
