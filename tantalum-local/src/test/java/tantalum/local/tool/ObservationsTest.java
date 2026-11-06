package tantalum.local.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class ObservationsTest {

    /** 只在 Windows 上跳过的原因。 */
    static final String MAC_CONNECTOR = "连接程序目前只在 macOS 上运行：Windows 上 fileKey() 为空，换掉的文件只按大小和时间认不出来，归 Windows 连接程序 spec";

    private static final String ONE = "one";
    private static final String TWO = "two";

    @TempDir
    Path root;

    private final Observations observations = new Observations();

    private Path file(String content) throws Exception {
        Path file = root.resolve("a.txt");
        Files.writeString(file, content);
        return file.toRealPath();
    }

    @Test
    void aFileThatWasNeverReadCannotBeChanged() throws Exception {
        assertEquals(Observations.Check.NOT_READ, observations.check(ONE, file("x")));
    }

    /**
     * 连接程序不知道会话什么时候结束（phase 3 留下的风险）：记录只留最近用过的 {@value Observations#MAX_SESSIONS} 个会话，
     * 内存不随会话数增长；被忘掉的会话要先重新读才能改，最近还在用的会话不受影响。
     */
    @Test
    void onlyTheMostRecentSessionsAreRemembered() throws Exception {
        Path file = file("x");
        observations.record(ONE, file);
        for (int i = 0; i < Observations.MAX_SESSIONS; i++) {
            observations.record("other-" + i, file);
            assertEquals(Observations.Check.OK, observations.check("other-0", file), "still in use");
        }
        assertEquals(Observations.Check.NOT_READ, observations.check(ONE, file), "the oldest session is forgotten");
        assertEquals(Observations.Check.OK, observations.check("other-0", file), "a recently used one is kept");
    }

    @Test
    void aFileReadAndUnchangedCanBeChanged() throws Exception {
        Path file = file("x");
        observations.record(ONE, file);
        assertEquals(Observations.Check.OK, observations.check(ONE, file));
    }

    @Test
    void aChangeAfterTheReadIsNoticed() throws Exception {
        Path file = file("x");
        observations.record(ONE, file);
        Files.writeString(file, "y");
        assertEquals(Observations.Check.CHANGED, observations.check(ONE, file));
    }

    /** 同样大小、只改修改时间（比如别的工具 touch 过）也算改过。 */
    @Test
    void aNewModificationTimeCountsAsAChange() throws Exception {
        Path file = file("x");
        observations.record(ONE, file);
        Files.setLastModifiedTime(file, FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 5000));
        assertEquals(Observations.Check.CHANGED, observations.check(ONE, file));
    }

    /** 改名替换会换 inode，大小和时间碰巧一样也认得出来。 */
    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = MAC_CONNECTOR)
    void aReplacedFileCountsAsAChange() throws Exception {
        Path file = file("x");
        observations.record(ONE, file);
        FileTime time = Files.getLastModifiedTime(file);
        Path other = Files.writeString(root.resolve("b.txt"), "z");
        Files.setLastModifiedTime(other, time);
        Files.move(other, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        assertEquals(Observations.Check.CHANGED, observations.check(ONE, file));
    }

    @Test
    void afterOwnWritesTheFileNeedsNoRereading() throws Exception {
        Path file = file("x");
        observations.record(ONE, file);
        TextFiles.writeAtomically(file, "edited".getBytes());
        observations.record(ONE, file);
        assertEquals(Observations.Check.OK, observations.check(ONE, file));
    }

    /** 一个连接程序只服务一个用户，用户由它在执行前核对（任务 10）；记录只按会话分。 */
    @Test
    void sessionsDoNotShareRecords() throws Exception {
        Path file = file("x");
        observations.record(ONE, file);
        assertEquals(Observations.Check.NOT_READ, observations.check(TWO, file));
    }

    @Test
    void forgettingASessionMeansReadingAgain() throws Exception {
        Path file = file("x");
        observations.record(ONE, file);
        observations.record(TWO, file);
        observations.forget(ONE);
        assertEquals(Observations.Check.NOT_READ, observations.check(ONE, file));
        assertEquals(Observations.Check.OK, observations.check(TWO, file));
    }

    /** 两个会话都读过同一文件，同时改：在锁里检查、写、记新版本，只有一个成功，另一个看到“改过”。 */
    @Test
    void concurrentEditsOfOneFileLetOnlyOneThrough() throws Exception {
        Path file = file("x");
        observations.record(ONE, file);
        observations.record(TWO, file);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Observations.Check>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String session : List.of(ONE, TWO)) {
                results.add(pool.submit(() -> {
                    start.await();
                    return observations.locked(file, () -> {
                        Observations.Check check = observations.check(session, file);
                        if (check == Observations.Check.OK) {
                            TextFiles.writeAtomically(file, session.getBytes());
                            observations.record(session, file);
                        }
                        return check;
                    });
                }));
            }
            start.countDown();
            List<Observations.Check> checks = new ArrayList<>();
            for (Future<Observations.Check> result : results) {
                checks.add(result.get());
            }
            assertEquals(1, checks.stream().filter(check -> check == Observations.Check.OK).count(), checks.toString());
            assertEquals(1, checks.stream().filter(check -> check == Observations.Check.CHANGED).count(), checks.toString());
        }
    }
}
