package tantalum.shell.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import tantalum.local.fs.PrivateFiles;
import tantalum.shell.attachment.AttachmentLimits;
import tantalum.shell.attachment.AttachmentStore;
import tantalum.shell.attachment.ImageNormalizer;
import tantalum.shell.auth.UserStore;
import tantalum.shell.memory.MemoryLimits;
import tantalum.shell.memory.MemoryStore;
import tantalum.value.attachment.AttachmentRef;
import tantalum.value.event.EventEnvelope;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;

/**
 * 只在真实的 Windows 上有意义的文件行为（windows-server 设计“Windows 上才跑的测试”）：ACL 继承、超过 260 字符的路径、保留名、
 * 替换时目标被占用、两个进程一份数据。数据目录检查的真实情况在 {@code DataDirCheckTest} 里（它是包内类）。
 * CI 上 {@code assert-not-skipped.ps1} 确认这个类真的跑了。
 */
@EnabledOnOs(value = OS.WINDOWS, disabledReason = "Windows 的 ACL、路径长度、保留名、共享冲突和 LockFileEx，只在 Windows 上有")
class WindowsFilesTest {

    private static final Principal ALICE = new Principal("alice");
    /** runner 是英文系统，按名字比对可行；产品代码不按名字判断（N3）。 */
    private static final Set<String> OTHERS = Set.of("Everyone", "BUILTIN\\Users", "NT AUTHORITY\\Authenticated Users");

    private final EventCodec codec = EventCodec.withBuiltins();

    @TempDir
    Path temp;

    // ---- ACL 继承（需求 2.1）----

    @Test
    void filesInTheDataDirectoryInheritAnAclWithoutOtherUsers() throws Exception {
        Path data = temp.resolve("data");
        try (StoreLock lock = StoreLock.acquire(data); UserStore users = UserStore.open(data)) {
            users.add("alice", "pw");
        }
        SessionPaths paths = new SessionPaths(data);
        writeSession(paths, new SessionId("s-1"), "hello");
        AttachmentRef ref = attachments(data).upload(ALICE, "a.txt", "text/plain",
                new ByteArrayInputStream("hi".getBytes(StandardCharsets.UTF_8)), 2);

        List<Path> created = List.of(data.resolve(UserStore.FILE), data.resolve("store.lock"), paths.sessionFile(ALICE, new SessionId("s-1")),
                paths.userDir(ALICE), attachments(data).find(ALICE, ref.id()).orElseThrow().file());
        for (Path file : created) {
            for (AclEntry entry : Files.getFileAttributeView(file, AclFileAttributeView.class).getAcl()) {
                boolean readable = entry.type() == AclEntryType.ALLOW && entry.permissions().contains(AclEntryPermission.READ_DATA);
                assertFalse(readable && OTHERS.contains(entry.principal().getName()), file + " lets " + entry.principal().getName() + " read");
            }
        }
    }

    // ---- 超过 260 字符的路径（需求 3.4）----

    @Test
    void sessionsAttachmentsAndMemoriesWorkBeyond260Characters() throws Exception {
        Path data = temp;
        while (data.toString().length() < 200) {
            data = data.resolve("deep-directory-" + data.getNameCount());
        }
        SessionPaths paths = new SessionPaths(data);
        SessionId session = new SessionId("s".repeat(80));
        assertTrue(paths.sessionFile(ALICE, session).toString().length() > 260, paths.sessionFile(ALICE, session).toString());
        writeSession(paths, session, "first");
        assertEquals("first", text(new SessionFileReader(codec).readAll(paths.sessionFile(ALICE, session)).getFirst()));

        AttachmentStore store = attachments(data);
        AttachmentRef ref = store.upload(ALICE, "long.txt", "text/plain", new ByteArrayInputStream("long".getBytes(StandardCharsets.UTF_8)), 4);
        assertEquals("long", new String(store.bytes(ALICE, ref.id()), StandardCharsets.UTF_8));

        MemoryStore memory = new MemoryStore(data.resolve("workspaces").resolve("alice").resolve("memory"),
                new MemoryLimits(200, 16384, 8192), Clock.systemUTC());
        memory.write("a-memory-with-a-long-name-" + "x".repeat(30), "user", "first", "one");
        memory.write("a-memory-with-a-long-name-" + "x".repeat(30), "user", "second", "two");
        assertEquals("two", memory.read("a-memory-with-a-long-name-" + "x".repeat(30)).orElseThrow().content());
    }

    // ---- 保留名（需求 3.2）----

    @Test
    void aSessionCalledNulReallyLandsInAFile() throws Exception {
        SessionPaths paths = new SessionPaths(temp);
        SessionId nul = new SessionId("nul");
        writeSession(paths, nul, "kept");
        Path file = paths.userDir(ALICE).resolve("%6eul.jsonl");
        assertEquals(file, paths.sessionFile(ALICE, nul));
        assertTrue(Files.size(file) > 0);
        assertEquals("kept", text(new SessionFileReader(codec).readAll(file).getFirst()));
    }

    // ---- 替换时目标被占用（需求 4.1）----

    @Test
    void aTargetReadThroughNioCanBeReplaced() throws Exception {
        Path target = Files.writeString(temp.resolve("format.json"), "old");
        Path source = Files.writeString(temp.resolve("format.json.tmp"), "new");
        try (FileChannel reading = FileChannel.open(target, StandardOpenOption.READ)) {
            PrivateFiles.replace(source, target);
        }
        assertEquals("new", Files.readString(target));
    }

    @Test
    void aTargetHeldWithoutDeleteSharingIsReplacedOnceReleased() throws Exception {
        Path target = Files.writeString(temp.resolve("memory.md"), "old");
        Path source = Files.writeString(temp.resolve("memory.md.tmp"), "new");
        FileInputStream held = new FileInputStream(target.toFile());
        Thread release = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(300);
                held.close();
            } catch (Exception ignored) {
                // 测试线程会因为替换失败而报告
            }
        });
        PrivateFiles.replace(source, target);
        release.join();
        assertEquals("new", Files.readString(target));
    }

    @Test
    void aTargetThatStaysHeldFailsWithinASecondAndTheCallerRemovesItsTemporaryFile() throws Exception {
        Path target = Files.writeString(temp.resolve("memory.md"), "old");
        Path source = Files.writeString(temp.resolve("memory.md.tmp"), "new");
        try (FileInputStream held = new FileInputStream(target.toFile())) {
            long started = System.nanoTime();
            try {
                assertThrows(FileSystemException.class, () -> PrivateFiles.replace(source, target));
            } finally {
                // 调用方的写法（MemoryStore、AttachmentStore、StoreFormat）：finally 里删临时文件
                Files.deleteIfExists(source);
            }
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(millis >= 900 && millis < 3000, "gave up after " + millis + " ms");
        }
        assertEquals("old", Files.readString(target));
        assertFalse(Files.exists(source));
    }

    // ---- 两个进程一份数据（需求 4.3）----

    @Test
    void aSecondProcessIsRefusedUntilTheFirstIsKilled() throws Exception {
        Path data = Files.createDirectories(temp.resolve("data"));
        Process holder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), LockHolder.class.getName(), data.toString())
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try (BufferedReader out = new BufferedReader(new InputStreamReader(holder.getInputStream(), StandardCharsets.UTF_8))) {
            assertEquals("locked", out.readLine());
            IllegalStateException refused = assertThrows(IllegalStateException.class, () -> StoreLock.acquire(data));
            assertTrue(refused.getMessage().contains("locked by another process"), refused.getMessage());
        } finally {
            holder.destroyForcibly();
            assertTrue(holder.waitFor(10, TimeUnit.SECONDS));
        }
        try (StoreLock lock = StoreLock.acquire(data)) {
            assertTrue(Files.exists(data.resolve("store.lock")));
        }
    }

    /** 子 JVM：拿着存储锁不放，直到被结束。 */
    static final class LockHolder {
        public static void main(String[] args) throws Exception {
            StoreLock lock = StoreLock.acquire(Path.of(args[0]));
            System.out.println("locked");
            System.out.flush();
            Thread.sleep(Long.MAX_VALUE);
            lock.close();
        }
    }

    // ---- 工具 ----

    private void writeSession(SessionPaths paths, SessionId session, String text) throws Exception {
        try (SessionFiles files = new SessionFiles(paths, codec, 4, (principal, id, seq, offset) -> { })) {
            files.append(ALICE, session, List.of(ResultWriter.noEffect(SessionFileTest.event(ALICE, session, 1, text))));
        }
    }

    private static String text(EventEnvelope envelope) {
        return ((tantalum.value.event.UserPrompt) envelope.event()).text();
    }

    private static AttachmentStore attachments(Path data) throws Exception {
        AttachmentLimits limits = AttachmentLimits.defaults();
        return new AttachmentStore(data, limits, new ImageNormalizer(limits));
    }
}
