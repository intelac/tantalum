package tantalum.shell.store;

import java.util.List;
import tantalum.shell.conversation.Transcript;
import tantalum.value.state.RunState;
import tantalum.value.state.SessionState;

/** 一个会话截至 {@code seq} 的内存状态，{@code offset} 是那个事件那一行结束的文件位置。可丢弃：丢了就全量重放。不含用户级用量，重放尾部时仍按序号注入。 */
public record SessionSnapshot(SessionState session, List<RunState> runs, Transcript.Saved transcript, long seq, long offset) {

    public SessionSnapshot {
        runs = List.copyOf(runs);
    }
}
