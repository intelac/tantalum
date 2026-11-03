package tantalum.shell.loop;

import java.util.List;
import tantalum.shell.conversation.Transcript;
import tantalum.shell.trace.Trace;
import tantalum.value.state.RunState;
import tantalum.value.state.SessionState;

/** 一个会话截至 {@code seq} 的内存状态：会话、它的运行和保存的对话、轨迹投影。不含用户级用量。 */
public record SessionSlice(SessionState session, List<RunState> runs, Transcript.Saved transcript, Trace.Saved trace, long seq) {

    public SessionSlice {
        runs = List.copyOf(runs);
    }
}
