package tantalum.shell.store;

import java.sql.SQLException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tantalum.shell.conversation.SessionSummary;
import tantalum.shell.conversation.Transcript;
import tantalum.shell.loop.StateView;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;
import tantalum.value.state.KernelState;
import tantalum.value.state.RunState;
import tantalum.value.state.SessionState;

/** 读会话和运行：在内存的取当前状态，不在的从会话文件重放，不进常驻。别人的会话和运行一律查不到。 */
public final class SessionQueries {

    /** 一个会话的只读视图。 */
    public record SessionView(SessionState state, List<RunState> runs, Transcript.View transcript) {}

    /** 会话文件读不了（中间损坏、索引库不可用）。 */
    public static final class SessionUnreadable extends RuntimeException {
        SessionUnreadable(SessionId session, Exception cause) {
            super("cannot read session " + session.value(), cause);
        }
    }

    private final StateView view;
    private final Transcript transcript;
    private final SessionIndex index;
    private final ColdReads cold;
    private final TurnQuery turns;
    private final TraceQuery trace;

    public SessionQueries(StateView view, Transcript transcript, SessionIndex index, ColdReads cold, TurnQuery turns, TraceQuery trace) {
        this.view = view;
        this.transcript = transcript;
        this.index = index;
        this.cold = cold;
        this.turns = turns;
        this.trace = trace;
    }

    /** 回放出的每次模型调用和轨迹。 */
    public TurnQuery turns() {
        return turns;
    }

    /** 整个会话的轨迹记录和请求详情，每次重放会话文件。 */
    public TraceQuery trace() {
        return trace;
    }

    public Optional<RunState> run(Principal principal, RunId id) {
        if (id.session().isEmpty()) {
            return Optional.empty();
        }
        RunState resident = view.current().runs().get(id);
        if (resident != null) {
            return Optional.of(resident).filter(run -> run.ref().principal().equals(principal));
        }
        return session(principal, id.session().get())
                .flatMap(found -> found.runs().stream().filter(run -> run.ref().run().equals(id)).findFirst());
    }

    public Optional<SessionView> session(Principal principal, SessionId session) {
        KernelState state = view.current();
        SessionState resident = state.sessions().get(session);
        if (resident != null) {
            if (!resident.principal().equals(principal)) {
                return Optional.empty();
            }
            List<RunState> runs = state.runs().values().stream().filter(run -> run.ref().session().equals(session)).toList();
            return Optional.of(new SessionView(resident, runs, transcript.view(session)));
        }
        try {
            return cold.read(principal, session).map(found -> new SessionView(found.session(), found.runs(),
                    new Transcript.View(found.transcript().lastSeq(), found.transcript().items())));
        } catch (InterruptedException stopping) {
            Thread.currentThread().interrupt();
            throw new SessionUnreadable(session, stopping);
        } catch (Exception failure) {
            throw new SessionUnreadable(session, failure);
        }
    }

    /** 侧栏的会话列表：读索引，在内存的会话用当前状态覆盖，因为索引可能还没写到最新。 */
    public List<SessionSummary> sessions(Principal principal) throws SQLException {
        KernelState state = view.current();
        Map<SessionId, SessionSummary> summaries = new HashMap<>();
        for (SessionIndex.Session row : index.sessions(principal)) {
            summaries.put(row.session(), new SessionSummary(row.session(), row.title(), row.lastActivity(), row.runStatus()));
        }
        for (SessionState session : state.sessions().values()) {
            if (session.principal().equals(principal)) {
                summaries.put(session.id(), SessionSummary.of(session.id(),
                        session.activeRun() == null ? null : state.runs().get(session.activeRun()), transcript.items(session.id())));
            }
        }
        return summaries.values().stream().sorted(Comparator.comparing(SessionSummary::lastActivity).reversed()).toList();
    }
}
