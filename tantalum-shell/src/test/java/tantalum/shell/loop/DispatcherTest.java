package tantalum.shell.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tantalum.shell.handler.Handler;
import tantalum.value.command.AbortCommands;
import tantalum.value.command.AssembleContext;
import tantalum.value.command.Command;
import tantalum.value.command.StartTimer;
import tantalum.value.event.CommandDropped;
import tantalum.value.event.Event;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.ModelFailed;
import tantalum.value.event.TimerFired;
import tantalum.value.id.CommandId;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;
import tantalum.value.model.FailureReason;

class DispatcherTest {

    private static final RunRef RUN = new RunRef(new Principal("p"), new SessionId("s"), new RunId("r"));
    private static final Command CONTEXT = new AssembleContext(new CommandId("c"), RUN);

    private final BlockingQueue<EventEnvelope> results = new LinkedBlockingQueue<>();
    private final InFlightCommands inFlight = new InFlightCommands();
    private InputRing ring;
    private ResultPublisher publisher;

    @BeforeEach
    void start() {
        ring = new InputRing(64, (slot, seq, end) -> results.add(slot.envelope), (slot, seq, end) -> { });
        ring.start();
        publisher = new ResultPublisher(ring, inFlight, Clock.systemUTC(), 0);
    }

    @AfterEach
    void stop() {
        ring.shutdown();
    }

    private Dispatcher dispatcher(Handler handler) {
        return new Dispatcher(Map.of(AssembleContext.class, handler), inFlight, publisher);
    }

    private static void send(Dispatcher dispatcher, Command command) throws Exception {
        OutputRing.Slot slot = new OutputRing.Slot();
        slot.command = command;
        dispatcher.onEvent(slot, 0, true);
    }

    private static Handler handler(Handler.Body body) {
        return new Handler() {
            @Override public Event handle(Command command) throws Exception { return body.run(command); }
            @Override public Event failed(Command command, Exception failure) {
                return new ModelFailed(command.id(), FailureReason.UNKNOWN, false, null, null, failure.getMessage());
            }
        };
    }

    @Test
    void handlerResultReturnsToTheRing() throws Exception {
        send(dispatcher(handler(command -> new TimerFired(command.id()))), CONTEXT);
        EventEnvelope result = results.poll(5, TimeUnit.SECONDS);
        assertInstanceOf(TimerFired.class, result.event());
    }

    @Test
    void hungHandlerDoesNotHoldTheConsumerThread() throws Exception {
        CountDownLatch hold = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(2);
        Dispatcher dispatcher = dispatcher(handler(command -> {
            running.countDown();
            hold.await();
            return null;
        }));
        long start = System.nanoTime();
        send(dispatcher, CONTEXT);
        send(dispatcher, new AssembleContext(new CommandId("d"), RUN));
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 5);
        assertTrue(running.await(5, TimeUnit.SECONDS));
        hold.countDown();
    }

    @Test
    void handlerExceptionBecomesItsFailureEvent() throws Exception {
        send(dispatcher(handler(command -> { throw new IllegalStateException("boom"); })), CONTEXT);
        ModelFailed failed = assertInstanceOf(ModelFailed.class, results.poll(5, TimeUnit.SECONDS).event());
        assertEquals("boom", failed.detail());
    }

    @Test
    void commandWithoutAHandlerIsReportedAsDropped() throws Exception {
        Dispatcher dispatcher = dispatcher(handler(command -> null));
        send(dispatcher, new StartTimer(new CommandId("t"), RUN, java.time.Duration.ZERO));
        publisher.flushDropped();
        assertInstanceOf(CommandDropped.class, results.poll(5, TimeUnit.SECONDS).event());
    }

    @Test
    void abortCommandInterruptsTheHandler() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        Dispatcher dispatcher = dispatcher(handler(command -> {
            started.countDown();
            Thread.sleep(60_000);
            return null;
        }));
        send(dispatcher, CONTEXT);
        assertTrue(started.await(5, TimeUnit.SECONDS));
        send(dispatcher, new AbortCommands(new CommandId("a"), RUN, Set.of(CONTEXT.id())));
        assertInstanceOf(ModelFailed.class, results.poll(5, TimeUnit.SECONDS).event());
        waitForEmpty();
    }

    private void waitForEmpty() throws InterruptedException {
        for (int i = 0; i < 100 && inFlight.size() > 0; i++) {
            Thread.sleep(10);
        }
        assertEquals(0, inFlight.size());
    }

    @Test
    void completedCommandsLeaveNoTrace() throws Exception {
        Dispatcher dispatcher = dispatcher(handler(command -> new TimerFired(command.id())));
        for (int i = 0; i < 20; i++) {
            send(dispatcher, new AssembleContext(new CommandId("c" + i), RUN));
        }
        for (int i = 0; i < 20; i++) {
            results.poll(5, TimeUnit.SECONDS);
        }
        waitForEmpty();
    }
}
