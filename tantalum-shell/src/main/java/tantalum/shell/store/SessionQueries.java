package tantalum.shell.store;

import java.sql.SQLException;
import java.time.LocalDate;
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
import tantalum.value.model.TokenUsage;
import tantalum.value.state.AwaitingAnswer;
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
    private final RunQuery runs;

    public SessionQueries(StateView view, Transcript transcript, SessionIndex index, ColdReads cold, TurnQuery turns, TraceQuery trace,
                          RunQuery runs) {
        this.view = view;
        this.transcript = transcript;
        this.index = index;
        this.cold = cold;
        this.turns = turns;
        this.trace = trace;
        this.runs = runs;
    }

    /** 回放出的每次模型调用和轨迹。 */
    public TurnQuery turns() {
        return turns;
    }

    /** 整个会话的轨迹记录和请求详情，每次重放会话文件。 */
    public TraceQuery trace() {
        return trace;
    }

    /** 运行的调用树和跨会话的运行列表（run-tree）。 */
    public RunQuery runs() {
        return runs;
    }

    public Optional<RunState> run(Principal principal, RunId id) {
        if (id.session().isEmpty()) {
            return Optional.empty();
        }
        RunState resident = view.current().runs().get(id);
        if (resident != null) {
            return Optional.of(resident).filter(run -> run.ref().principal().equals(principal));
        }
        return everyRun(principal, id.session().get()).stream().filter(run -> run.ref().run().equals(id)).findFirst();
    }

    /**
     * 这个会话的每一轮（live-views）：内存里只留正在跑的和最近结束的，所以从账里读全部，再用内存里的盖上（内存可能比文件新）。
     * 会话不存在、不是本人的为空列表。
     */
    public List<RunState> everyRun(Principal principal, SessionId session) {
        KernelState state = view.current();
        SessionState resident = state.sessions().get(session);
        if (resident != null && !resident.principal().equals(principal)) {
            return List.of();
        }
        java.util.Map<RunId, RunState> all = new java.util.LinkedHashMap<>();
        for (RunState run : runs.everyRun(principal, session)) {
            all.put(run.ref().run(), run);
        }
        state.runs().values().stream().filter(run -> run.ref().session().equals(session) && run.ref().principal().equals(principal))
                .forEach(run -> all.put(run.ref().run(), run));
        return all.values().stream().sorted(java.util.Comparator.comparingLong(run -> run.ref().run().seq())).toList();
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
                    found.transcript().view()));
        } catch (InterruptedException stopping) {
            Thread.currentThread().interrupt();
            throw new SessionUnreadable(session, stopping);
        } catch (Exception failure) {
            throw new SessionUnreadable(session, failure);
        }
    }

    /** 这个会话正在等用户回答的问题：活动运行停在 {@code AwaitingAnswer} 时有；不在内存的会话从文件读。 */
    public Optional<AwaitingAnswer> openQuestion(Principal principal, SessionId session) {
        return waitingRun(principal, session).map(run -> (AwaitingAnswer) run.phase());
    }

    /** 同 {@link #openQuestion}，给出整个运行：要知道问的是哪次工具调用时用。 */
    public Optional<RunState> waitingRun(Principal principal, SessionId session) {
        return session(principal, session).flatMap(found -> {
            RunId active = found.state().activeRun();
            return active == null ? Optional.empty() : found.runs().stream()
                    .filter(run -> run.ref().run().equals(active))
                    .filter(run -> run.phase() instanceof AwaitingAnswer)
                    .findFirst();
        });
    }

    /** 这个用户在 {@code day}（UTC）用了多少 token。从索引读，比刚落盘的记录最多晚一次批量写入。 */
    public TokenUsage usageOn(Principal principal, LocalDate day) throws SQLException {
        return index.usageOn(principal, day);
    }

    /** 这个人的各个会话放出去还没回来的任务数（侧栏的标记）：读索引，在内存的会话用当前状态。没有的不在里面。 */
    public Map<SessionId, Integer> openTasks(Principal principal) throws SQLException {
        Map<SessionId, Integer> counts = new HashMap<>(index.openTaskCounts(principal));
        for (SessionState session : view.current().sessions().values()) {
            if (session.principal().equals(principal)) {
                if (session.assignments().open().isEmpty()) {
                    counts.remove(session.id());
                } else {
                    counts.put(session.id(), session.assignments().open().size());
                }
            }
        }
        return counts;
    }

    /** 一个会话放出过的任务各自的会话（含已经结束的），按会话号排；在内存的用当前状态。 */
    public List<SessionSummary> tasksOf(Principal principal, SessionId parent) throws SQLException {
        KernelState state = view.current();
        List<SessionSummary> found = new java.util.ArrayList<>();
        for (SessionIndex.Session row : index.tasksOf(principal, parent)) {
            SessionState live = state.sessions().get(row.session());
            String status = live == null ? row.runStatus()
                    : live.activeRun() == null ? SessionSummary.IDLE : state.runs().get(live.activeRun()).phase().name();
            found.add(new SessionSummary(row.session(), row.shownTitle(), row.lastActivity(), status, row.dir(), row.offRecord()));
        }
        return found;
    }

    /**
     * 这个人哪些会话的运行可以告诉个人插件：不是“不进记忆”的，种类照表也通知（帮手会话不通知）。读索引，在内存的用当前状态；
     * 不读会话文件（补通知时每一轮都要问）。不在里面的当作不该记，宁可少记。
     */
    public java.util.Set<SessionId> notable(Principal principal) throws SQLException {
        java.util.Set<SessionId> notable = new java.util.HashSet<>();
        for (SessionIndex.Session row : index.allOf(principal)) {
            if (!row.offRecord() && tantalum.shell.profile.SessionProfile.of(row.kind()).notifiesPlugins()) {
                notable.add(row.session());
            }
        }
        for (SessionState session : view.current().sessions().values()) {
            if (session.principal().equals(principal)) {
                if (!session.offRecord() && tantalum.shell.profile.SessionProfile.of(session.kind()).notifiesPlugins()) {
                    notable.add(session.id());
                } else {
                    notable.remove(session.id());
                }
            }
        }
        return notable;
    }

    /** 侧栏的会话列表：读索引，在内存的会话用当前状态覆盖，因为索引可能还没写到最新。 */
    public List<SessionSummary> sessions(Principal principal) throws SQLException {
        KernelState state = view.current();
        Map<SessionId, SessionSummary> summaries = new HashMap<>();
        Map<SessionId, String> renamed = new HashMap<>();
        for (SessionIndex.Session row : index.sessions(principal)) {
            summaries.put(row.session(), new SessionSummary(row.session(), row.shownTitle(), row.lastActivity(), row.runStatus(), row.dir(),
                    row.offRecord()));
            if (row.renamed() != null) {
                renamed.put(row.session(), row.renamed());
            }
        }
        for (SessionState session : state.sessions().values()) {
            // 照表：帮手会话不列，它跟着交代它的会话看
            if (session.principal().equals(principal) && tantalum.shell.profile.SessionProfile.of(session.kind()).listed()
                    && !session.id().equals(tantalum.value.id.SessionIds.main(principal))) {
                // 在内存的会话：状态和最近活动取当前的；改过的名字只记在索引里（内核不存标题）
                SessionSummary live = SessionSummary.of(session.id(),
                        session.activeRun() == null ? null : state.runs().get(session.activeRun()), transcript.view(session.id()));
                summaries.put(session.id(), new SessionSummary(session.id(), renamed.getOrDefault(session.id(), live.title()),
                        live.lastActivity(), live.runStatus(), session.dir(), session.offRecord()));
            }
        }
        return summaries.values().stream().sorted(Comparator.comparing(SessionSummary::lastActivity).reversed()).toList();
    }

    /** 按内容搜到的会话：命中那条的角色和片段（session-find-pin R3）。 */
    public record ContentHit(SessionId session, String role, String snippet) {}

    /** 片段最多几个字。 */
    public static final int SNIPPET = 120;

    /** 按内容搜这个用户的会话，最多 {@code limit} 个。读索引，比刚落盘的记录最多晚一次批量写入。 */
    public List<ContentHit> search(Principal principal, String query, int limit) throws SQLException {
        return index.search(principal, query, limit).stream()
                .map(hit -> new ContentHit(hit.session(), hit.role(), Snippet.around(hit.text(), query, SNIPPET))).toList();
    }
}
