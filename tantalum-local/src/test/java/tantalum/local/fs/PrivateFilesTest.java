package tantalum.local.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 需求 1.3（POSIX 上照旧 600/700）、4.1（Windows 上替换被占用时短暂重试）。 */
class PrivateFilesTest {

    private static final String WINDOWS_ACL = "Windows 上权限靠继承的 ACL，见 WindowsFilesTest";

    @TempDir
    Path temp;

    // ---- POSIX 上的权限 ----

    @Test
    void newFilesAreOwnerOnly() throws IOException {
        assumeTrue(PrivateFiles.posix(temp), WINDOWS_ACL);
        Path file = temp.resolve("users.db");
        PrivateFiles.createFile(file);
        Path tempFile = PrivateFiles.createTempFile(temp, "format.json", ".tmp");

        assertEquals("rw-------", mode(file));
        assertEquals("rw-------", mode(tempFile));
    }

    @Test
    void everyNewDirectoryLevelIsOwnerOnlyAndExistingParentsAreLeftAlone() throws IOException {
        assumeTrue(PrivateFiles.posix(temp), WINDOWS_ACL);
        Path existing = Files.createDirectory(temp.resolve("existing"));
        Files.setPosixFilePermissions(existing, PosixFilePermissions.fromString("rwxr-xr-x"));

        PrivateFiles.createDirectories(existing.resolve("a/b"));

        assertEquals("rwxr-xr-x", mode(existing));
        assertEquals("rwx------", mode(existing.resolve("a")));
        assertEquals("rwx------", mode(existing.resolve("a/b")));
    }

    @Test
    void restrictTightensExistingFilesAndDirectories() throws IOException {
        assumeTrue(PrivateFiles.posix(temp), WINDOWS_ACL);
        Path file = Files.writeString(temp.resolve("store.lock"), "");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        Path dir = Files.createDirectory(temp.resolve("workspace"));
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));

        PrivateFiles.restrict(file);
        PrivateFiles.restrictDirectory(dir);

        assertEquals("rw-------", mode(file));
        assertEquals("rwx------", mode(dir));
    }

    @Test
    void copyPermissionsKeepsTheOriginalMode() throws IOException {
        assumeTrue(PrivateFiles.posix(temp), WINDOWS_ACL);
        Path original = Files.writeString(temp.resolve("notes.txt"), "old");
        Files.setPosixFilePermissions(original, PosixFilePermissions.fromString("rw-r-----"));
        Path replacement = temp.resolve("notes.tmp");
        PrivateFiles.createFile(replacement);

        PrivateFiles.copyPermissions(original, replacement);

        assertEquals("rw-r-----", mode(replacement));
    }

    // ---- 替换 ----

    @Test
    void replaceSwapsTheTargetAtomically() throws IOException {
        Path target = Files.writeString(temp.resolve("format.json"), "old");
        Path source = Files.writeString(temp.resolve("format.json.tmp"), "new");

        PrivateFiles.replace(source, target);

        assertEquals("new", Files.readString(target));
        assertTrue(Files.notExists(source));
    }

    @Test
    void onWindowsABusyTargetIsRetriedUntilItIsFree() throws IOException {
        List<Long> sleeps = new ArrayList<>();
        int[] calls = {0};
        PrivateFiles.replace(temp.resolve("a"), temp.resolve("b"), (source, target) -> {
            calls[0]++;
            if (calls[0] == 1) {
                throw new AccessDeniedException(target.toString());
            }
            if (calls[0] == 2) {
                // 共享冲突时 JDK 抛的正好是 FileSystemException 本身。
                throw new FileSystemException(target.toString(), null, "being used by another process");
            }
        }, true, sleeps::add);

        assertEquals(3, calls[0]);
        assertEquals(List.of(10L, 20L), sleeps);
    }

    @Test
    void onWindowsATargetThatStaysBusyFailsWithinTheBudgetWithTheLastError() {
        List<Long> sleeps = new ArrayList<>();
        List<AccessDeniedException> thrown = new ArrayList<>();
        AccessDeniedException last = assertThrows(AccessDeniedException.class,
                () -> PrivateFiles.replace(temp.resolve("a"), temp.resolve("b"), (source, target) -> {
                    AccessDeniedException failure = new AccessDeniedException(target.toString());
                    thrown.add(failure);
                    throw failure;
                }, true, sleeps::add));

        assertSame(thrown.getLast(), last);
        assertEquals(PrivateFiles.REPLACE_BUDGET_MILLIS, sleeps.stream().mapToLong(Long::longValue).sum());
        assertEquals(List.of(10L, 20L, 40L, 80L, 160L, 200L, 200L, 200L, 90L), sleeps);
        assertEquals(sleeps.size() + 1, thrown.size());
    }

    @Test
    void errorsThatRetryingCannotFixAreNotRetried() {
        List<Long> sleeps = new ArrayList<>();
        assertThrows(NoSuchFileException.class, () -> PrivateFiles.replace(temp.resolve("a"), temp.resolve("b"),
                (source, target) -> {
                    throw new NoSuchFileException(source.toString());
                }, true, sleeps::add));
        assertEquals(List.of(), sleeps);
    }

    @Test
    void offWindowsABusyTargetIsNotRetried() {
        List<Long> sleeps = new ArrayList<>();
        assertThrows(AccessDeniedException.class, () -> PrivateFiles.replace(temp.resolve("a"), temp.resolve("b"),
                (source, target) -> {
                    throw new AccessDeniedException(target.toString());
                }, false, sleeps::add));
        assertEquals(List.of(), sleeps);
    }

    // ---- 目录落盘 ----

    @Test
    void syncDirectoryWorksOnThisPlatform() throws IOException {
        // POSIX 上真的落盘；Windows 上跳过，不能抛 AccessDeniedException（需求 4.2）。
        PrivateFiles.syncDirectory(temp);
    }

    private static String mode(Path path) throws IOException {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
    }
}
