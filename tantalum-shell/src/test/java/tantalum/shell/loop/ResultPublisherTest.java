package tantalum.shell.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tantalum.value.command.AssembleContext;
import tantalum.value.command.Command;
import tantalum.value.event.CommandDropped;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.TimerFired;
import tantalum.value.id.CommandId;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;

class ResultPublisherTest {

    private static final RunRef RUN = new RunRef(new Principal("p"), new SessionId("s"), new RunId("r"));
    private static final Instant NOW = Instant.parse("2026-10-31T00:00:00Z");

    private final CountDownLatch release = new CountDownLatch(1);
    private final List<EventEnvelope> received = new CopyOnWriteArrayList<>();
    private final InFlightCommands inFlight = new InFlightCommands();
    private InputRing ring;

    private ResultPublisher publisher(int size, int reserve) {
        ring = new InputRing(size, (slot, seq, end) -> {
            release.await();
            received.add(slot.envelope);
        }, (slot, seq, end) -> { });
        ring.start();
        return new ResultPublisher(ring, inFlight, Clock.fixed(NOW, ZoneOffset.UTC), reserve);
    }

    private static Command command(String id) {
        return new AssembleContext(new CommandId(id), RUN);
    }

    private void drain(int expected) throws Exception {
        release.countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (received.size() < expected && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }

    @AfterEach
    void stop() {
        release.countDown();
        ring.shutdown();
    }

    @Test
    void resultCarriesTheRunAndTheCurrentTime() throws Exception {
        ResultPublisher publisher = publisher(8, 0);
        publisher.publish(command("a"), new TimerFired(new CommandId("a")));
        drain(1);
        EventEnvelope envelope = received.getFirst();
        assertEquals(NOW, envelope.at());
        assertEquals(RUN.run(), envelope.run());
        assertEquals(RUN.principal(), envelope.principal());
        assertEquals(RUN.session(), envelope.session());
    }

    @Test
    void abortedResultIsDroppedWhenCapacityIsReservedForLiveRuns() throws Exception {
        ResultPublisher publisher = publisher(8, 8);
        Command aborted = command("a");
        inFlight.register(aborted.id(), Thread.ofVirtual().unstarted(() -> { }));
        inFlight.abort(Set.of(aborted.id()));

        publisher.publish(aborted, new TimerFired(aborted.id()));
        drain(0);
        Thread.sleep(50);

        assertEquals(List.of(), received);
    }

    @Test
    void abortedResultIsKeptWhenThereIsRoom() throws Exception {
        ResultPublisher publisher = publisher(8, 2);
        Command aborted = command("a");
        inFlight.register(aborted.id(), Thread.ofVirtual().unstarted(() -> { }));
        inFlight.abort(Set.of(aborted.id()));

        publisher.publish(aborted, new TimerFired(aborted.id()));
        drain(1);

        assertInstanceOf(TimerFired.class, received.getFirst().event());
    }

    @Test
    void liveResultThatDoesNotFitBecomesCommandDropped() throws Exception {
        ResultPublisher publisher = publisher(2, 0);
        for (int i = 0; i < 8; i++) {
            publisher.publish(command("c" + i), new TimerFired(new CommandId("c" + i)));
        }
        assertTrue(publisher.pendingDrops() > 0);
        int drops = publisher.pendingDrops();

        release.countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (publisher.pendingDrops() > 0 && System.nanoTime() < deadline) {
            publisher.flushDropped();
        }
        drain(8);

        assertEquals(0, publisher.pendingDrops());
        assertEquals(drops, received.stream().filter(e -> e.event() instanceof CommandDropped).count());
    }

    @Test
    void reportedDropEventuallyReachesTheRing() throws Exception {
        ResultPublisher publisher = publisher(8, 0);
        publisher.reportDropped(command("x"), "no handler");
        publisher.flushDropped();
        drain(1);
        CommandDropped dropped = assertInstanceOf(CommandDropped.class, received.getFirst().event());
        assertEquals("no handler", dropped.detail());
    }
}
