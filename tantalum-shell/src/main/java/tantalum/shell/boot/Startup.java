package tantalum.shell.boot;

import java.util.List;
import tantalum.kernel.Kernel;
import tantalum.shell.conversation.Transcript;
import tantalum.shell.store.SessionStore;
import tantalum.value.event.EventEnvelope;
import tantalum.value.state.KernelState;
import tantalum.value.state.RunState;

/** 从会话文件重建全部状态和对话条目。未结束的运行由 {@code Engine} 随后发布 {@code RunInterrupted} 收尾，这里不改状态。 */
public final class Startup {

    public record Recovered(KernelState state, Transcript transcript) {

        /** 重启前没有结束的运行。 */
        public List<RunState> unfinished() {
            return state.runs().values().stream().filter(run -> !run.ended()).toList();
        }
    }

    private Startup() {}

    public static Recovered recover(SessionStore store, Kernel kernel) {
        List<EventEnvelope> events = store.readAll();
        Transcript transcript = new Transcript();
        return new Recovered(transcript.replay(kernel, events), transcript);
    }
}
