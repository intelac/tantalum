package tantalum.shell.journal;

import java.io.IOException;
import java.util.List;
import tantalum.kernel.Kernel;
import tantalum.value.decision.Decision;
import tantalum.value.decision.StepRecord;
import tantalum.value.event.EventEnvelope;
import tantalum.value.state.KernelState;

/** 测试用：像线上一样写会话文件，每个事件后面跟着内核处理它的结果行（record-results）。 */
public final class ResultWriter implements AutoCloseable {

    private final SessionFiles files;
    private final Kernel kernel;
    private KernelState state = KernelState.empty();

    public ResultWriter(SessionPaths paths, EventCodec codec, Kernel kernel) {
        this.files = new SessionFiles(paths, codec, 4, (principal, session, seq, offset) -> { });
        this.kernel = kernel;
    }

    public Decision write(EventEnvelope envelope) throws IOException {
        Decision decision = kernel.step(state, envelope);
        files.appendLines(envelope.principal(), envelope.session(),
                List.of(new Line.Event(envelope), new Line.Result(StepRecord.of(envelope, state, decision))));
        state = decision.newState();
        return decision;
    }

    @Override
    public void close() throws IOException {
        files.close();
    }
}
