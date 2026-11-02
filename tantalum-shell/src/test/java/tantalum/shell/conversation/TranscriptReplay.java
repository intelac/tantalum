package tantalum.shell.conversation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import tantalum.kernel.Kernel;
import tantalum.value.decision.Decision;
import tantalum.value.event.EventEnvelope;
import tantalum.value.id.Principal;
import tantalum.value.state.KernelState;

/** 测试用的全量回放：每个事件先过内核再投影，和实时路径一样。服务本身只按会话回放（{@code SessionReplay}）。 */
public final class TranscriptReplay {

    private TranscriptReplay() {}

    /** 返回折叠出的状态；{@code transcript} 被投影成回放后的样子。 */
    public static KernelState run(Transcript transcript, Kernel kernel, List<EventEnvelope> events) {
        KernelState state = KernelState.empty();
        Map<Principal, Long> previous = new HashMap<>();
        for (EventEnvelope envelope : events) {
            Long last = previous.put(envelope.principal(), envelope.seq());
            if (last != null && envelope.seq() <= last) {
                throw new IllegalArgumentException("event out of order at seq " + envelope.seq());
            }
            Decision decision = kernel.step(state, envelope);
            state = decision.newState();
            transcript.apply(envelope, decision);
        }
        return state;
    }
}
