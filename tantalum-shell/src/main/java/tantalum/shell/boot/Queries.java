package tantalum.shell.boot;

import tantalum.shell.conversation.Transcript;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.journal.RecordCodec;
import tantalum.shell.loop.Projections;
import tantalum.shell.loop.StateView;
import tantalum.shell.sse.SseHub;
import tantalum.shell.store.ColdReads;
import tantalum.shell.store.SessionQueries;
import tantalum.shell.store.TraceQuery;
import tantalum.shell.store.TurnQuery;
import tantalum.shell.trace.Trace;

/** 装配第三步：读取端——内存里的状态视图、对话和轨迹的投影、推送中枢、接口的查询。 */
record Queries(StateView view, Transcript transcript, Projections projections, TurnQuery turns, SseHub hub, SessionQueries queries) {

    static Queries open(Stores stores, EventCodec codec) {
        Storage storage = stores.storage();
        Transcript transcript = new Transcript();
        Projections projections = new Projections(transcript, new Trace());
        StateView view = new StateView();
        TurnQuery turns = new TurnQuery(storage.paths, storage.records, codec.mapper());
        SseHub hub = new SseHub(1024, 4096, turns::messagesAfter, codec.mapper());
        SessionQueries queries = new SessionQueries(view, transcript, storage.index, new ColdReads(storage.paths, storage.records::read),
                turns, new TraceQuery(storage.paths, storage.records, new RecordCodec(codec)));
        return new Queries(view, transcript, projections, turns, hub, queries);
    }
}
