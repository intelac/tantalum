package tantalum.shell.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import tantalum.value.command.StartTimer;
import tantalum.value.event.TimerFired;
import tantalum.value.id.CommandId;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;

class TimerHandlerTest {

    private static final StartTimer TIMER = new StartTimer(new CommandId("t"),
            new RunRef(new Principal("p"), new SessionId("s"), new RunId("r")), Duration.ofMillis(1));

    @Test
    void firesAfterTheDelay() throws Exception {
        assertEquals(new TimerFired(TIMER.id()), new TimerHandler().handle(TIMER));
    }

    @Test
    void canBeInterrupted() {
        StartTimer longTimer = new StartTimer(TIMER.id(), TIMER.run(), Duration.ofMinutes(1));
        Thread.currentThread().interrupt();
        assertThrows(InterruptedException.class, () -> new TimerHandler().handle(longTimer));
    }

    @Test
    void failureStillFiresBecauseAStaleResultIsHarmless() {
        assertEquals(new TimerFired(TIMER.id()), new TimerHandler().failed(TIMER, new InterruptedException()));
    }
}
