package tantalum.value.state;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;

/** 整棵状态树。不可变，每次更新返回新对象。 */
public record KernelState(Map<Principal, PrincipalState> principals,
                          Map<SessionId, SessionState> sessions,
                          Map<RunId, RunState> runs) {

    public KernelState {
        principals = Map.copyOf(principals);
        sessions = Map.copyOf(sessions);
        runs = Map.copyOf(runs);
    }

    public static KernelState empty() {
        return new KernelState(Map.of(), Map.of(), Map.of());
    }

    public KernelState with(PrincipalState principal) {
        Map<Principal, PrincipalState> updated = new HashMap<>(principals);
        updated.put(principal.principal(), principal);
        return new KernelState(updated, sessions, runs);
    }

    public KernelState with(SessionState session) {
        Map<SessionId, SessionState> updated = new HashMap<>(sessions);
        updated.put(session.id(), session);
        return new KernelState(principals, updated, runs);
    }

    public KernelState with(RunState run) {
        Map<RunId, RunState> updated = new HashMap<>(runs);
        updated.put(run.ref().run(), run);
        return new KernelState(principals, sessions, updated);
    }

    /** 放回一个会话和它的运行。只由外壳在载入时调用，不是事件。 */
    public KernelState withResident(SessionState session, List<RunState> sessionRuns) {
        Map<SessionId, SessionState> keptSessions = new HashMap<>(sessions);
        keptSessions.put(session.id(), session);
        Map<RunId, RunState> keptRuns = new HashMap<>(runs);
        sessionRuns.forEach(run -> keptRuns.put(run.ref().run(), run));
        return new KernelState(principals, keptSessions, keptRuns);
    }

    /** 去掉一个会话和它的运行。只由外壳在回收时调用，不是事件。 */
    public KernelState without(SessionId session) {
        Map<SessionId, SessionState> keptSessions = new HashMap<>(sessions);
        keptSessions.remove(session);
        Map<RunId, RunState> keptRuns = new HashMap<>(runs);
        keptRuns.values().removeIf(run -> run.ref().session().equals(session));
        return new KernelState(principals, keptSessions, keptRuns);
    }

    public long activeRunCount() {
        return runs.values().stream().filter(run -> !run.ended()).count();
    }
}
