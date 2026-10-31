package tantalum.shell.boot;

import java.util.List;
import tantalum.kernel.Kernel;
import tantalum.shell.conversation.Transcript;
import tantalum.shell.journal.Journal;
import tantalum.shell.loop.SubmissionLedger;
import tantalum.value.event.EventEnvelope;
import tantalum.value.state.KernelState;
import tantalum.value.state.RunState;

/**
 * 从 journal 重建全部状态和对话条目。未结束的运行保持原等待状态并标记“待恢复”，不重发命令，可以取消。
 */
public final class Startup {

    public record Recovered(KernelState state, long nextSeq, Transcript transcript) {}

    private Startup() {}

    public static Recovered recover(Journal journal, Kernel kernel, SubmissionLedger ledger) {
        List<EventEnvelope> events = journal.readAll();
        Transcript transcript = new Transcript();
        KernelState state = transcript.replay(kernel, events);
        for (RunState run : state.runs().values()) {
            if (!run.ended()) {
                state = state.with(run.withPendingRecovery(true));
            }
        }
        ledger.rebuild(events);
        return new Recovered(state, events.isEmpty() ? 0 : events.getLast().seq() + 1, transcript);
    }
}
