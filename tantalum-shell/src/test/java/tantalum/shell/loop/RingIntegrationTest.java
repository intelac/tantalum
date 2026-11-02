package tantalum.shell.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.kernel.Kernel;
import tantalum.kernel.Replay;
import tantalum.kernel.hook.HookChain;
import tantalum.kernel.table.BuiltinSteps;
import tantalum.kernel.table.TransitionTable;
import tantalum.shell.handler.Handler;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.store.SessionFileReader;
import tantalum.shell.store.SessionFiles;
import tantalum.shell.store.SessionPaths;
import tantalum.shell.store.SessionStore;
import tantalum.value.command.AssembleContext;
import tantalum.value.command.CallModel;
import tantalum.value.command.Command;
import tantalum.value.event.ContextAssembled;
import tantalum.value.event.Event;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.ModelFailed;
import tantalum.value.event.ModelReplied;
import tantalum.value.event.UserPrompt;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.SessionId;
import tantalum.value.id.SubmissionId;
import tantalum.value.model.FailureReason;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tantalum.value.state.EndReason;
import tantalum.value.state.Ended;
import tantalum.value.state.KernelState;
import tantalum.value.state.OwnerRef;
import tantalum.value.state.RunState;

/** 真实的环、journal 和内核，只有 Handler 是假的。 */
class RingIntegrationTest {

    @TempDir
    Path dir;

    private static Kernel kernel() {
        TransitionTable table = new TransitionTable();
        BuiltinSteps.register(table);
        return new Kernel(table, new HookChain(List.of()), (tool, args) -> false, 10);
    }

    private static Handler handler(Handler.Body body) {
        return new Handler() {
            @Override public Event handle(Command command) throws Exception { return body.run(command); }
            @Override public Event failed(Command command, Exception failure) {
                return new ModelFailed(command.id(), FailureReason.UNKNOWN, false, null, null, failure.toString());
            }
        };
    }

    @Test
    void submittedEventsReachTheEndAndReplayMatches() throws Exception {
        int sessions = 20;
        SessionPaths paths = new SessionPaths(dir);
        SessionFileReader reader = new SessionFileReader(EventCodec.withBuiltins());
        try (SessionFiles files = new SessionFiles(paths, EventCodec.withBuiltins(), 8)) {
            StateView view = new StateView(KernelState.empty());
            InFlightCommands inFlight = new InFlightCommands();
            Kernel kernel = kernel();
            InputRing input = new InputRing(1024);
            ResultPublisher publisher = new ResultPublisher(input, inFlight, Clock.systemUTC(), 640);
            Dispatcher dispatcher = new Dispatcher(Map.of(
                    AssembleContext.class, handler(c -> new ContextAssembled(c.id(), "system", List.of())),
                    CallModel.class, handler(c -> new ModelReplied(c.id(), List.of(new TextBlock("done")),
                            StopReason.END_TURN, "m", null))), inFlight, publisher);
            OutputRing output = new OutputRing(1024, dispatcher);
            CleanroomRing ring = new CleanroomRing(1024);
            JournalDispatcher journal = new JournalDispatcher(1024, new TailSequence(paths), files::append, ring::tryPublish);
            List<String> notDurable = java.util.Collections.synchronizedList(new ArrayList<>());
            Cleanroom cleanroom = new Cleanroom(kernel, KernelState.empty(), journal::halted, output::tryPublish, Projection.NONE,
                    (seq, index, fact) -> { }, view, publisher);
            ring.connect((slot, seq, end) -> {
                EventEnvelope envelope = slot.envelope;
                if (reader.readAll(paths.sessionFile(envelope.principal(), envelope.session())).stream().noneMatch(envelope::equals)) {
                    notDurable.add(envelope.toString());
                }
                cleanroom.onEvent(slot, seq, end);
                journal.released();
            });
            input.connect(journal);
            ring.start();
            output.start();
            input.start();

            for (int i = 0; i < sessions; i++) {
                SessionId session = new SessionId("s" + i);
                assertTrue(input.tryPublish(new EventEnvelope(EventEnvelope.UNASSIGNED, Instant.EPOCH,
                        new Principal("u" + (i % 3)), session, null, new UserPrompt(new SubmissionId("sub" + i),
                                new RequestId("req" + i), new OwnerRef.OfSession(session), "hi"))));
            }

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline && !allEnded(view.current(), sessions)) {
                Thread.sleep(5);
            }
            assertTrue(allEnded(view.current(), sessions), "runs: " + view.current().runs().values());
            for (RunState run : view.current().runs().values()) {
                assertEquals(EndReason.COMPLETED, assertInstanceOf(Ended.class, run.phase()).reason());
            }

            input.shutdown();
            ring.shutdown(java.time.Duration.ofSeconds(5));
            output.shutdown();

            assertEquals(List.of(), notDurable, "events must be durable before the kernel sees them");
            List<EventEnvelope> events = new SessionStore(dir, EventCodec.withBuiltins()).readAll();
            assertEquals(sessions * 3, events.size());
            assertEquals(view.current().runs(), Replay.run(kernel, events).state().runs());
            assertEquals(view.current().sessions(), Replay.run(kernel, events).state().sessions());
        }
    }

    private static boolean allEnded(KernelState state, int sessions) {
        return state.runs().size() == sessions && state.activeRunCount() == 0;
    }
}
