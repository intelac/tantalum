package tantalum.shell.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** record-results 任务 12：{@code check} 命令读服务器的配置建内核，不开端口、不写数据目录。 */
class RecordCheckCommandTest {

    @TempDir
    Path dir;

    @Test
    void anEmptyDataDirectoryChecksCleanWithoutBeingTouched() throws Exception {
        Path data = Files.createDirectories(dir.resolve("data"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        int code = RecordCheckCommand.run(new String[] {"--tantalum.data-dir=" + data, "--tantalum.workspace=" + dir.resolve("ws")},
                new PrintStream(out, true, StandardCharsets.UTF_8));

        assertEquals(0, code, out.toString(StandardCharsets.UTF_8));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("all 0 sessions"));
        try (var files = Files.list(data)) {
            assertEquals(0, files.count(), "the check writes nothing");
        }
    }
}
