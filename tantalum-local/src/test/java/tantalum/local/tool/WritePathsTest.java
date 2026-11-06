package tantalum.local.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.local.fs.AllowedDirs;

/**
 * 服务器上的 {@code WritePathsTest} 测的是 {@code memory/} 的规则，那条规则不搬到电脑上（见 {@link WritePaths}）。这里测剩下的两件事：
 * 写入路径走 {@link AllowedDirs#resolveForWrite}，先读后写的说明告诉模型下一步怎么做（C5）。
 */
class WritePathsTest {

    @TempDir
    Path root;

    @Test
    void writesResolveInsideTheAllowedDirectoriesOnly() throws Exception {
        AllowedDirs dirs = new AllowedDirs(List.of(root));
        Files.createDirectory(root.resolve("memory"));
        assertEquals(root.toRealPath().resolve("memory/x.md"), WritePaths.resolve(dirs, "memory/x.md"));
        assertThrows(IllegalArgumentException.class, () -> WritePaths.resolve(dirs, "../x.md"));
    }

    @Test
    void refusalsTellTheModelWhatToDoNext() {
        assertNull(WritePaths.refusal(Observations.Check.OK, "a.txt"));
        String notRead = WritePaths.refusal(Observations.Check.NOT_READ, "a.txt");
        assertTrue(notRead.startsWith("cannot modify \"a.txt\": file has not been read in this session — read it with read_file"), notRead);
        assertTrue(notRead.contains("connector on the user's computer restarts"), notRead);
        assertEquals("cannot modify \"a.txt\": file changed since it was read — read it again, then retry",
                WritePaths.refusal(Observations.Check.CHANGED, "a.txt"));
    }
}
