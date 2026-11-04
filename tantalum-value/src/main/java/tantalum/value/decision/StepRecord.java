package tantalum.value.decision;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tantalum.value.command.Command;
import tantalum.value.event.EventEnvelope;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;
import tantalum.value.model.TokenUsage;
import tantalum.value.state.KernelState;
import tantalum.value.state.PrincipalState;
import tantalum.value.state.RunState;
import tantalum.value.state.SessionState;

/**
 * 内核处理一个事件的结果，只含变了的部分：写进会话文件，恢复时 {@link #apply} 到处理前的状态上，不再调内核。
 * 约定：一步只改事件所在的会话和它的运行，会话的消息只追加；违反时 {@link #of} 抛出，不悄悄写错。
 * {@code usage} 是这一步给事件用户加的用量（差值，跨会话的累计值单个文件折叠不出来），没有时为 null。
 */
public record StepRecord(long seq, SessionChange session, List<RunState> runs, List<RunId> removedRuns,
                         LocalDate usageDay, TokenUsage usage, List<Command> commands, List<Fact> facts) {

    public StepRecord {
        runs = List.copyOf(runs);
        removedRuns = List.copyOf(removedRuns);
        commands = List.copyOf(commands);
        facts = List.copyOf(facts);
        if ((usageDay == null) != (usage == null)) {
            throw new IllegalArgumentException("usageDay and usage go together");
        }
    }

    public static StepRecord of(EventEnvelope envelope, KernelState before, Decision decision) {
        KernelState after = decision.newState();
        SessionId sessionId = envelope.session();
        SessionChange session = null;
        for (Map.Entry<SessionId, SessionState> entry : after.sessions().entrySet()) {
            SessionState was = before.sessions().get(entry.getKey());
            if (was == entry.getValue()) {
                continue;
            }
            if (!entry.getKey().equals(sessionId)) {
                throw new IllegalStateException("seq " + envelope.seq() + " changed session " + entry.getKey().value()
                        + " outside its own session " + sessionId.value());
            }
            SessionChange change = SessionChange.between(was, entry.getValue());
            session = change.isEmpty() ? null : change;
        }
        for (SessionId gone : before.sessions().keySet()) {
            if (!after.sessions().containsKey(gone)) {
                throw new IllegalStateException("seq " + envelope.seq() + " removed session " + gone.value());
            }
        }
        List<RunState> runs = new ArrayList<>();
        for (RunState run : after.runs().values()) {
            RunState was = before.runs().get(run.ref().run());
            if (was == run || run.equals(was)) {
                continue;
            }
            ownRun(envelope, run.ref().session());
            runs.add(run);
        }
        runs.sort((a, b) -> a.ref().run().value().compareTo(b.ref().run().value()));
        List<RunId> removed = new ArrayList<>();
        for (RunState run : before.runs().values()) {
            if (!after.runs().containsKey(run.ref().run())) {
                ownRun(envelope, run.ref().session());
                removed.add(run.ref().run());
            }
        }
        removed.sort((a, b) -> a.value().compareTo(b.value()));
        LocalDate day = null;
        TokenUsage usage = null;
        for (Map.Entry<Principal, PrincipalState> entry : after.principals().entrySet()) {
            PrincipalState was = before.principals().get(entry.getKey());
            if (was == entry.getValue() || entry.getValue().equals(was)) {
                continue;
            }
            if (!entry.getKey().equals(envelope.principal())) {
                throw new IllegalStateException("seq " + envelope.seq() + " changed the usage of " + entry.getKey().value());
            }
            PrincipalState base = was == null ? PrincipalState.start(entry.getKey()) : was;
            for (Map.Entry<LocalDate, TokenUsage> daily : entry.getValue().dailyUsage().entrySet()) {
                if (daily.getValue().equals(base.dailyUsage().get(daily.getKey()))) {
                    continue;
                }
                if (day != null) {
                    throw new IllegalStateException("seq " + envelope.seq() + " changed usage on more than one day");
                }
                day = daily.getKey();
                usage = daily.getValue().minus(base.usageOn(day));
            }
        }
        return new StepRecord(envelope.seq(), session, runs, removed, day, usage, decision.commands(), decision.facts());
    }

    /** 套到处理前的状态上，得到和当时内核相同的决定。 */
    public Decision apply(EventEnvelope envelope, KernelState before) {
        KernelState state = before;
        if (session != null) {
            state = state.with(session.applyTo(before.sessions().get(session.id())));
        }
        for (RunState run : runs) {
            state = state.with(run);
        }
        if (!removedRuns.isEmpty()) {
            Map<RunId, RunState> kept = new HashMap<>(state.runs());
            removedRuns.forEach(kept::remove);
            state = new KernelState(state.principals(), state.sessions(), kept);
        }
        if (usage != null) {
            PrincipalState current = state.principals().get(envelope.principal());
            state = state.with((current == null ? PrincipalState.start(envelope.principal()) : current).add(usageDay, usage));
        }
        return new Decision(state, commands, facts);
    }

    private static void ownRun(EventEnvelope envelope, SessionId runSession) {
        if (!Objects.equals(runSession, envelope.session())) {
            throw new IllegalStateException("seq " + envelope.seq() + " changed a run of session " + runSession.value()
                    + " outside its own session " + envelope.session().value());
        }
    }
}
