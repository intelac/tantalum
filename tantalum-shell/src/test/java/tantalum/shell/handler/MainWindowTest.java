package tantalum.shell.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.value.id.SessionId;
import tantalum.value.state.CompactionSettings;

/** memory-final-plan 第 11 节：主窗口按编号认；压缩点是固定部分加预算，保留预算是一半，都不超过模型的容量。 */
class MainWindowTest {

    private static final CompactionSettings BASE = new CompactionSettings(200_000, 16_000, 20_000, 32_768, 4096, 8192, 150_000, 3);

    @Test
    void aMainWindowKeepsOnlyItsBudgetAboveTheFixedPart() {
        assertTrue(MainWindow.is(new SessionId("main-2026-11-16")));
        assertFalse(MainWindow.is(new SessionId("s-1")));
        String system = "x".repeat(30_000);
        CompactionSettings settings = MainWindow.settings(BASE, system, List.of(), 8000);
        assertEquals(10_000 + 8000, settings.threshold(), "30 000 bytes of system at 3 bytes a token, plus the budget");
        assertEquals(4000, settings.keepRecent());
        assertEquals(BASE.window(), settings.window());
        CompactionSettings huge = MainWindow.settings(BASE, "x".repeat(900_000), List.of(), 8000);
        assertEquals(BASE.capacity(), huge.threshold(), "never above what the model can take");
    }
}
