package tantalum.shell.loop;

import tantalum.shell.conversation.Transcript;
import tantalum.shell.journal.Snapshot;
import tantalum.shell.trace.Trace;
import tools.jackson.databind.json.JsonMapper;

/**
 * 会话切片（{@link SessionSlice}）和账里的快照（{@link Snapshot}）互转。账只认得会话状态和运行，两个投影的状态在这里编成 JSON：
 * 写快照的净室和从快照载入的回放用同一个转法，所以从快照载入的投影和一直在内存的一样。
 */
public final class Snapshots {

    private final JsonMapper mapper;

    /** {@code mapper} 是账的那一个（{@code EventCodec.mapper()}）。 */
    public Snapshots(JsonMapper mapper) {
        this.mapper = mapper;
    }

    public Snapshot of(SessionSlice slice) {
        return new Snapshot(slice.seq(), slice.session(), slice.runs(), mapper.valueToTree(slice.transcript()), mapper.valueToTree(slice.trace()));
    }

    public SessionSlice slice(Snapshot snapshot) {
        return new SessionSlice(snapshot.session(), snapshot.runs(), mapper.treeToValue(snapshot.transcript(), Transcript.Saved.class),
                mapper.treeToValue(snapshot.trace(), Trace.Saved.class), snapshot.seq());
    }
}
