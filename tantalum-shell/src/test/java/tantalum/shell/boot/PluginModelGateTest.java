package tantalum.shell.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tantalum.shell.plugin.PluginServices.Priority;

/** memory-final-plan 第 15 节：插件调模型按路线排队，名额用完就等；前台的排在后台的前面。 */
class PluginModelGateTest {

    @Test
    void aFullRouteMakesCallersWaitAndGivesUpAfterTheWait() throws Exception {
        PluginModelGate gate = new PluginModelGate(route -> 1);
        PluginModelGate.Permit first = gate.acquire("local", Priority.BACKGROUND, Duration.ofSeconds(1));
        assertNotNull(first);
        assertNull(gate.acquire("local", Priority.FOREGROUND, Duration.ofMillis(50)), "the only slot is taken");
        assertNotNull(gate.acquire("claude", Priority.BACKGROUND, Duration.ofMillis(50)), "routes do not share slots");
        first.close();
        first.close();
        assertEquals(0, gate.used("local"), "closing twice frees once");
    }

    @Test
    void aWaitingForegroundCallGoesBeforeWaitingBackgroundCalls() throws Exception {
        PluginModelGate gate = new PluginModelGate(route -> 1);
        PluginModelGate.Permit busy = gate.acquire("local", Priority.BACKGROUND, Duration.ofSeconds(1));
        List<String> order = new CopyOnWriteArrayList<>();
        CountDownLatch background = new CountDownLatch(1);
        Thread later = Thread.ofPlatform().start(() -> {
            try {
                background.countDown();
                try (PluginModelGate.Permit permit = gate.acquire("local", Priority.BACKGROUND, Duration.ofSeconds(5))) {
                    order.add("background");
                }
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            }
        });
        background.await(1, TimeUnit.SECONDS);
        Thread.sleep(50);
        Thread first = Thread.ofPlatform().start(() -> {
            try (PluginModelGate.Permit permit = gate.acquire("local", Priority.FOREGROUND, Duration.ofSeconds(5))) {
                order.add("foreground");
                Thread.sleep(50);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            }
        });
        Thread.sleep(50);
        busy.close();
        first.join(5000);
        later.join(5000);
        assertEquals(List.of("foreground", "background"), order);
    }
}
