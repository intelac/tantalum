package tantalum.shell.loop;

import java.util.List;
import tantalum.shell.conversation.Transcript;
import tantalum.value.state.RunState;
import tantalum.value.state.SessionState;

/** 通道载入的冷会话，随事件进 Cleanroom Ring，cleanroom 处理事件前并入。{@code failure} 不为 null 时载入失败，其余字段为空。 */
public record Preload(SessionState session, List<RunState> runs, Transcript.Part transcript, long seq, int replayed, String failure) {

    public static Preload loaded(SessionState session, List<RunState> runs, Transcript.Part transcript, long seq, int replayed) {
        return new Preload(session, List.copyOf(runs), transcript, seq, replayed, null);
    }

    public static Preload failed(String reason) {
        return new Preload(null, List.of(), null, -1, 0, reason);
    }

    public boolean isFailure() {
        return failure != null;
    }
}
