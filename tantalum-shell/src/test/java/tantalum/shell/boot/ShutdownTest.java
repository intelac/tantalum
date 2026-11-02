package tantalum.shell.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ShutdownTest {

    @Test
    void aFailingStepDoesNotSkipTheRestAndTheFirstFailureIsThrown() {
        List<String> ran = new ArrayList<>();
        IOException first = new IOException("first");
        IOException run = assertThrows(IOException.class, () -> Shutdown.run(List.of(
                new Shutdown.Step("a", () -> ran.add("a")),
                new Shutdown.Step("b", () -> {
                    ran.add("b");
                    throw first;
                }),
                new Shutdown.Step("c", () -> {
                    ran.add("c");
                    throw new IllegalStateException("second");
                }),
                new Shutdown.Step("d", () -> ran.add("d")))));
        assertSame(first, run);
        assertEquals(List.of("a", "b", "c", "d"), ran);
    }

    @Test
    void aCheckedFailureThatIsNotIoIsWrapped() {
        Exception thrown = assertThrows(IllegalStateException.class,
                () -> Shutdown.run(List.of(new Shutdown.Step("x", () -> {
                    throw new InterruptedException("stop");
                }))));
        assertEquals(InterruptedException.class, thrown.getCause().getClass());
    }
}
