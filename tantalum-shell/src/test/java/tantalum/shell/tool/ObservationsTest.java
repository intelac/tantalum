package tantalum.shell.tool;

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
import org.junit.jupiter.api.io.TempDir;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;

class ObservationsTest {

    private static final Principal ALICE = new Principal("alice");
    private static final Principal BOB = new Principal("bob");
    private static final SessionId ONE = new SessionId("one");
    private static final SessionId TWO = new SessionId("two");

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
        assertEquals(Observations.Check.NOT_READ, observations.check(ALICE, ONE, file("x")));
    }

    @Test
    void aFileReadAndUnchangedCanBeChanged() throws Exception {
        Path file = file("x");
        observations.record(ALICE, ONE, file);
        assertEquals(Observations.Check.OK, observations.check(ALICE, ONE, file));
    }

    @Test
    void aChangeAfterTheReadIsNoticed() throws Exception {
        Path file = file("x");
        observations.record(ALICE, ONE, file);
        Files.writeString(file, "y");
        assertEquals(Observations.Check.CHANGED, observations.check(ALICE, ONE, file));
    }

    /** 同样大小、只改修改时间（比如别的工具 touch 过）也算改过。 */
    @Test
    void aNewModificationTimeCountsAsAChange() throws Exception {
        Path file = file("x");
        observations.record(ALICE, ONE, file);
        Files.setLastModifiedTime(file, FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 5000));
        assertEquals(Observations.Check.CHANGED, observations.check(ALICE, ONE, file));
    }

    /** 改名替换会换 inode，大小和时间碰巧一样也认得出来。 */
    @Test
    void aReplacedFileCountsAsAChange() throws Exception {
        Path file = file("x");
        observations.record(ALICE, ONE, file);
        FileTime time = Files.getLastModifiedTime(file);
        Path other = Files.writeString(root.resolve("b.txt"), "z");
        Files.setLastModifiedTime(other, time);
        Files.move(other, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        assertEquals(Observations.Check.CHANGED, observations.check(ALICE, ONE, file));
    }

    @Test
    void afterOwnWritesTheFileNeedsNoRereading() throws Exception {
        Path file = file("x");
        observations.record(ALICE, ONE, file);
        TextFiles.writeAtomically(file, "edited".getBytes());
        observations.record(ALICE, ONE, file);
        assertEquals(Observations.Check.OK, observations.check(ALICE, ONE, file));
    }

    @Test
    void sessionsAndUsersDoNotShareRecords() throws Exception {
        Path file = file("x");
        observations.record(ALICE, ONE, file);
        assertEquals(Observations.Check.NOT_READ, observations.check(ALICE, TWO, file));
        assertEquals(Observations.Check.NOT_READ, observations.check(BOB, ONE, file));
    }

    @Test
    void forgettingASessionMeansReadingAgain() throws Exception {
        Path file = file("x");
        observations.record(ALICE, ONE, file);
        observations.record(ALICE, TWO, file);
        observations.forget(ONE);
        assertEquals(Observations.Check.NOT_READ, observations.check(ALICE, ONE, file));
        assertEquals(Observations.Check.OK, observations.check(ALICE, TWO, file));
    }

    /** 两个会话都读过同一文件，同时改：在锁里检查、写、记新版本，只有一个成功，另一个看到“改过”。 */
    @Test
    void concurrentEditsOfOneFileLetOnlyOneThrough() throws Exception {
        Path file = file("x");
        observations.record(ALICE, ONE, file);
        observations.record(ALICE, TWO, file);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Observations.Check>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (SessionId session : List.of(ONE, TWO)) {
                results.add(pool.submit(() -> {
                    start.await();
                    return observations.locked(file, () -> {
                        Observations.Check check = observations.check(ALICE, session, file);
                        if (check == Observations.Check.OK) {
                            TextFiles.writeAtomically(file, session.value().getBytes());
                            observations.record(ALICE, session, file);
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
