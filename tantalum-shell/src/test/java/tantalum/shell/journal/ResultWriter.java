package tantalum.shell.journal;

import java.io.IOException;
import java.util.List;
import tantalum.kernel.Kernel;
import tantalum.kernel.hook.HookChain;
import tantalum.kernel.step.BuiltinSteps;
import tantalum.kernel.Limits;
import tantalum.kernel.table.TransitionTable;
import tantalum.value.decision.Decision;
import tantalum.value.decision.StepRecord;
import tantalum.value.event.EventEnvelope;
import tantalum.value.state.KernelState;

/**
 * 测试用：像线上一样写会话文件，每个事件交给内核处理，事件和结果写成一行（one-line-journal）。
 * 用来准备“上次进程停在某一步之后”的数据目录。
 */
public final class ResultWriter implements AutoCloseable {

    private final SessionFiles files;
    private final Kernel kernel;
    private KernelState state = KernelState.empty();

    public ResultWriter(SessionPaths paths, EventCodec codec, Kernel kernel) {
        this.files = new SessionFiles(paths, codec, 4, (principal, session, seq, offset) -> { });
        this.kernel = kernel;
    }

    /** 用不带钩子的内置内核。 */
    public ResultWriter(SessionPaths paths) {
        this(paths, EventCodec.withBuiltins(), plainKernel());
    }

    /** 按顺序处理并写下这些事件，一次写一个。 */
    public static void write(SessionPaths paths, List<EventEnvelope> events) throws IOException {
        try (ResultWriter writer = new ResultWriter(paths)) {
            for (EventEnvelope envelope : events) {
                writer.write(envelope);
            }
        }
    }

    public Decision write(EventEnvelope envelope) throws IOException {
        Decision decision = kernel.step(state, envelope);
        files.append(envelope.principal(), envelope.session(), List.of(new Line(envelope, StepRecord.of(envelope, state, decision))));
        state = decision.newState();
        return decision;
    }

    /** 一步什么都没改变（测试只关心文件里有这一行时用）。 */
    public static Line noEffect(EventEnvelope envelope) {
        return new Line(envelope, new StepRecord(envelope.seq(), null, List.of(), List.of(), null, null, List.of(), List.of()));
    }

    private static Kernel plainKernel() {
        TransitionTable table = new TransitionTable();
        BuiltinSteps.register(table);
        return new Kernel(table, new HookChain(List.of()), (tool, args) -> false, Limits.DEFAULT);
    }

    @Override
    public void close() throws IOException {
        files.close();
    }
}
