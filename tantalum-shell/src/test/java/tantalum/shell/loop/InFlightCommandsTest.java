package tantalum.shell.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tantalum.value.id.CommandId;

class InFlightCommandsTest {

    private static final CommandId ID = new CommandId("1-0");

    @Test
    void abortInterruptsTheThreadAndMarksTheCommand() throws Exception {
        InFlightCommands inFlight = new InFlightCommands();
        CountDownLatch interrupted = new CountDownLatch(1);
        Thread thread = Thread.ofVirtual().unstarted(() -> {
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException expected) {
                interrupted.countDown();
            }
        });
        inFlight.register(ID, thread);
        thread.start();
        assertFalse(inFlight.isAborted(ID));

        inFlight.abort(Set.of(ID));

        assertTrue(interrupted.await(5, TimeUnit.SECONDS));
        assertTrue(inFlight.isAborted(ID));
    }

    @Test
    void completeRemovesTheCommand() {
        InFlightCommands inFlight = new InFlightCommands();
        inFlight.register(ID, Thread.ofVirtual().unstarted(() -> { }));
        assertEquals(1, inFlight.size());
        inFlight.complete(ID);
        assertEquals(0, inFlight.size());
        assertFalse(inFlight.isAborted(ID));
    }

    @Test
    void abortingAnUnknownCommandDoesNothing() {
        InFlightCommands inFlight = new InFlightCommands();
        inFlight.abort(Set.of(ID));
        assertEquals(0, inFlight.size());
    }
}
