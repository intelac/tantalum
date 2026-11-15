package tantalum.shell.store;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** memory-final-plan 第 15 节：插件调模型的用量账，只插入；按人、插件、用途合计，失败的另数。 */
class PluginUsageTest {

    @TempDir
    Path dir;

    @Test
    void callsAreTotalledPerUserPluginAndPurpose() throws Exception {
        Instant t = Instant.parse("2026-11-16T01:00:00Z");
        try (PluginUsage usage = PluginUsage.open(dir)) {
            usage.record(new PluginUsage.Call(t, "yi", "memory", "capture", "local", "qwen", 1000, 100, 9000, "ok"));
            usage.record(new PluginUsage.Call(t, "yi", "memory", "capture", "local", "qwen", 500, 0, 30, "failed"));
            usage.record(new PluginUsage.Call(t, "yi", "memory", "rewrite", "local", "qwen", 200, 20, 1500, "ok"));
            usage.record(new PluginUsage.Call(t.minusSeconds(86_400 * 10), "bob", "memory", "capture", "local", "qwen", 9, 9, 1, "ok"));
        }
        try (PluginUsage reopened = PluginUsage.open(dir)) {
            assertEquals(List.of(new PluginUsage.Total("yi", "memory", "capture", 2, 1, 1500, 100),
                    new PluginUsage.Total("yi", "memory", "rewrite", 1, 0, 200, 20)), reopened.totals(t.minusSeconds(86_400 * 7)));
        }
    }
}
