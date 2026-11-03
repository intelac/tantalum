package tantalum.kernel;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tantalum.kernel.hook.HookChain;
import tantalum.kernel.step.BuiltinSteps;
import tantalum.kernel.table.TransitionTable;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.ModelReplied;
import tantalum.value.id.CommandId;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;
import tantalum.value.model.Message;
import tantalum.value.model.StopReason;
import tantalum.value.state.Awaiting;
import tantalum.value.state.KernelState;
import tantalum.value.state.OwnerRef;
import tantalum.value.state.RunState;
import tantalum.value.state.SessionContext;
import tantalum.value.state.SessionState;

/** 记录 64 个活跃运行、长会话下 {@code Kernel.step} 的 P99。门槛 5 ms，不含 journal 写入。 */
class KernelStepBenchmarkTest {

    private static final int ITERATIONS = 20_000;
    private static final int WARMUP = 5_000;

    @Test
    void p99StaysUnderFiveMillis() {
        TransitionTable table = new TransitionTable();
        BuiltinSteps.register(table);
        Kernel kernel = new Kernel(table, new HookChain(List.of()), (tool, args) -> false, Limits.DEFAULT);
        Principal user = new Principal("u");
        CommandId waiting = new CommandId("0-0");

        KernelState state = KernelState.empty();
        for (int i = 0; i < 64; i++) {
            SessionId session = new SessionId("s" + i);
            RunRef ref = new RunRef(user, session, new RunId("r" + i));
            SessionState history = SessionState.start(session, user);
            for (int m = 0; m < 500; m++) {
                history = history.append(Message.user("message " + m));
            }
            state = state.with(history.startRun(ref.run()).withContext(new SessionContext("system", List.of(), null, List.of())))
                    .with(RunState.start(ref, new OwnerRef.OfSession(session))
                            .withPhase(new Awaiting(Set.of(waiting), "AwaitingModel")));
        }

        ModelReplied reply = new ModelReplied(waiting, List.of(LoopFixture.call("c", "safe")), StopReason.TOOL_USE, "m", null, null, null, null);
        EventEnvelope envelope = new EventEnvelope(1, java.time.Instant.EPOCH, user, new SessionId("s0"),
                new RunId("r0"), reply);

        long[] nanos = new long[ITERATIONS];
        long sink = 0;
        for (int i = 0; i < WARMUP + ITERATIONS; i++) {
            long start = System.nanoTime();
            sink += kernel.step(state, envelope).commands().size();
            if (i >= WARMUP) {
                nanos[i - WARMUP] = System.nanoTime() - start;
            }
        }
        Arrays.sort(nanos);
        double p50 = nanos[ITERATIONS / 2] / 1e6;
        double p99 = nanos[(int) (ITERATIONS * 0.99)] / 1e6;
        System.out.printf("Kernel.step p50=%.3fms p99=%.3fms (sink=%d)%n", p50, p99, sink);
        assertTrue(p99 <= 5.0, "p99 was " + p99 + " ms");
    }
}
