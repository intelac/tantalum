package tantalum.shell.store;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.boot.Warmup;
import tantalum.shell.journal.EventCodec;

/**
 * one-line-journal 需求 6.1：jar 里预热用的小会话要能被当前代码读出来。会话文件的格式改了，这个测试就失败：
 * 用 {@code -Dwarmup.write=true} 重新生成（取 {@link ReplayScenario} 里最大的会话），一起提交。
 */
class WarmupFileTest {

    private static final Path RESOURCE = Path.of("src/main/resources/warmup/session.jsonl");

    @TempDir
    Path dir;

    @Test
    void theBuiltInSessionReadsWithTheCurrentCode() throws Exception {
        if (Boolean.getBoolean("warmup.write")) {
            try (ReplayScenario scenario = new ReplayScenario(dir, List.of())) {
                scenario.run();
                // 场景里最长的会话只有几轮：接着多问几十轮，压缩会再发生几次
                for (int i = 0; i < 40; i++) {
                    scenario.prompt(ReplayScenario.ALICE, ReplayScenario.COMPACTED, "warm up round " + i + " " + "words ".repeat(40));
                    scenario.drain();
                }
                scenario.fixture.files.close();
            }
            try (Stream<Path> files = Files.walk(new tantalum.shell.journal.SessionPaths(dir).sessionsDir())) {
                List<Path> sessions = files.filter(file -> file.toString().endsWith(".jsonl")).toList();
                Path largest = sessions.stream().max(Comparator.comparingLong(WarmupFileTest::size)).orElseThrow();
                Files.createDirectories(RESOURCE.getParent());
                Files.copy(largest, RESOURCE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
        int items = Warmup.run(EventCodec.withBuiltins());
        assertTrue(items >= 80, "the warmup session is too small to warm anything: " + items + " items");
    }

    private static long size(Path file) {
        try {
            return Files.size(file);
        } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }
}
