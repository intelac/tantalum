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
import tantalum.shell.journal.Journal;
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
        try (Journal journal = Journal.open(dir, EventCodec.withBuiltins())) {
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
            JournalWriter writer = new JournalWriter(journal, journal.nextSeq());
            List<Long> durableAtStep = new ArrayList<>();
            Cleanroom cleanroom = new Cleanroom(kernel, KernelState.empty(), writer::halted, output::tryPublish, Projection.NONE,
                    (seq, index, fact) -> { }, view, publisher);
            input.connect(writer, (slot, seq, end) -> {
                durableAtStep.add(slot.envelope.seq());
                assertTrue(journal.readAll().size() > slot.envelope.seq(), "event must be durable before the kernel sees it");
                cleanroom.onEvent(slot, seq, end);
            });
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
            output.shutdown();

            List<EventEnvelope> events = journal.readAll();
            assertEquals(sessions * 3, events.size());
            assertEquals(view.current(), Replay.run(kernel, events).state());
            for (int i = 1; i < durableAtStep.size(); i++) {
                assertEquals(durableAtStep.get(i - 1) + 1, durableAtStep.get(i));
            }
        }
    }

    private static boolean allEnded(KernelState state, int sessions) {
        return state.runs().size() == sessions && state.activeRunCount() == 0;
    }
}
