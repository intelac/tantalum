package tantalum.shell.loop;

import java.util.List;
import tantalum.shell.conversation.Transcript;
import tantalum.value.state.PrincipalState;
import tantalum.value.state.RunState;
import tantalum.value.state.SessionState;

/**
 * 通道载入的内容，随事件进 Cleanroom Ring，cleanroom 处理事件前并入。{@code session} 为空时只带用户用量；
 * {@code principal} 是这个用户在本进程第一个事件之前的累计用量，只随他的第一个事件来一次。
 * {@code failure} 不为 null 时会话载入失败，会话相关字段为空。
 */
public record Preload(SessionState session, List<RunState> runs, Transcript.Saved transcript, long seq, int replayed, String failure,
                      PrincipalState principal) {

    public static Preload loaded(SessionState session, List<RunState> runs, Transcript.Saved transcript, long seq, int replayed) {
        return new Preload(session, List.copyOf(runs), transcript, seq, replayed, null, null);
    }

    public static Preload failed(String reason) {
        return new Preload(null, List.of(), null, -1, 0, reason, null);
    }

    /** 只带用量：会话已经在内存。 */
    public static Preload usage(PrincipalState principal) {
        return new Preload(null, List.of(), null, -1, 0, null, principal);
    }

    public Preload withPrincipal(PrincipalState usage) {
        return new Preload(session, runs, transcript, seq, replayed, failure, usage);
    }

    public boolean isFailure() {
        return failure != null;
    }
}
