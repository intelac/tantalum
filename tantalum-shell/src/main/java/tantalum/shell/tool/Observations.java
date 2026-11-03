package tantalum.shell.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;

/**
 * 先读后写（需求 C5，照 DSH 的观察记录 {@code tool-fs/src/error.ts:29-42}）：按会话记下每个文件最后一次读到或写下时的版本。
 * 覆盖或修改已有文件前，这个会话必须读过它，而且之后没被改过。记录只在内存里：会话移出内存或服务重启后要重新读。
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

    private record Key(Principal principal, SessionId session) {}

    @FunctionalInterface
    interface Locked<T> {
        T run() throws IOException;
    }

    private final Map<Key, Map<Path, Version>> seen = new ConcurrentHashMap<>();
    private final ReentrantLock[] locks = new ReentrantLock[LOCK_STRIPES];

    public Observations() {
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    /** 写、改成功之后（仍持有文件锁时）调用，记下现在的版本。{@code file} 是真实路径。 */
    void record(Principal principal, SessionId session, Path file) throws IOException {
        record(principal, session, file, Version.of(file));
    }

    /** 读用：读之前取的版本。调用方确认读完时版本没变才记，否则模型看到的内容和记下的版本对不上。 */
    void record(Principal principal, SessionId session, Path file, Version version) {
        seen.computeIfAbsent(new Key(principal, session), ignored -> new ConcurrentHashMap<>()).put(file, version);
    }

    /** 已有文件能不能改。不存在的文件不用检查。 */
    Check check(Principal principal, SessionId session, Path file) throws IOException {
        Map<Path, Version> files = seen.get(new Key(principal, session));
        Version known = files == null ? null : files.get(file);
        if (known == null) {
            return Check.NOT_READ;
        }
        return known.equals(Version.of(file)) ? Check.OK : Check.CHANGED;
    }

    /** 同一个文件的“检查版本、写入、记下新版本”在进程内互斥，两个运行同时改只有一个成功（C8）。 */
    <T> T locked(Path file, Locked<T> action) throws IOException {
        ReentrantLock lock = locks[Math.floorMod(file.hashCode(), LOCK_STRIPES)];
        lock.lock();
        try {
            return action.run();
        } finally {
            lock.unlock();
        }
    }

    /** 会话移出内存时调用。移出时只知道会话 id，按 id 去掉。 */
    public void forget(SessionId session) {
        seen.keySet().removeIf(key -> key.session().equals(session));
    }
}
