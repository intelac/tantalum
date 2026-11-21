package tantalum.shell.journal;

import java.util.List;
import java.util.Objects;
import tantalum.value.state.RunState;
import tantalum.value.state.SessionState;
import tools.jackson.databind.JsonNode;

/**
 * 账里的一份快照：序号为 {@code seq} 的那一步处理完之后，这个会话在内存里的全部样子。会话状态和还在内存的运行由账自己编码；
 * 对话投影和轨迹投影的状态（{@code transcript}、{@code trace}）由它们的主人编码成 JSON 交进来，账只原样存取。
 */
public record Snapshot(long seq, SessionState session, List<RunState> runs, JsonNode transcript, JsonNode trace) {

    public Snapshot {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(transcript, "transcript");
        Objects.requireNonNull(trace, "trace");
        runs = List.copyOf(runs);
    }
}
