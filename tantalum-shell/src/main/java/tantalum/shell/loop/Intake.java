package tantalum.shell.loop;

import java.time.Clock;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import tantalum.kernel.compaction.Compactor;
import tantalum.value.event.Cancel;
import tantalum.value.event.CompactRequested;
import tantalum.value.event.Event;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.FollowUpSubmitted;
import tantalum.value.event.SteerSubmitted;
import tantalum.value.event.Submitted;
import tantalum.value.event.UserPrompt;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.RunId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;
import tantalum.value.id.SubmissionId;
import tantalum.value.state.OwnerRef;
import tantalum.value.state.SessionState;

/**
 * 外部提交的入口：提问、排队、插话、手动压缩、取消。去重、占名额之后发布到输入环；发布不成就全部退回。
 * 事件处理完后由 {@link #processed} 归还名额。
 */
public final class Intake {

    /** 提交的结果。{@code status} 对应 HTTP 状态。 */
    public record Submission(int status, SubmissionId submission, boolean duplicate) {

        static Submission rejected(int status) {
            return new Submission(status, null, false);
        }
    }

    /** 新会话 ID 第一次出现时认领它，返回 HTTP 状态：200 归本人，404 已属别人，503 记不下来。 */
    public interface Claims {
        int claim(Principal principal, SessionId session);
    }

    /** 会话的当前状态，不在内存时从文件读；不存在或不是本人的为空。 */
    public interface Sessions {
        Optional<SessionState> find(Principal principal, SessionId session);
    }

    private final InputRing input;
    private final ExternalGate gate;
    private final SubmissionLedger ledger;
    private final StateView view;
    private final Residency residency;
    private final Claims claims;
    private final Sessions sessions;
    private final Clock clock;
    /** 需要开新运行、还没处理完的提交：处理完时多归还一个活跃运行名额。 */
    private final Set<SubmissionId> newRuns = ConcurrentHashMap.newKeySet();

    public Intake(InputRing input, ExternalGate gate, SubmissionLedger ledger, StateView view, Residency residency,
                  Claims claims, Sessions sessions, Clock clock) {
        this.input = input;
        this.gate = gate;
        this.ledger = ledger;
        this.view = view;
        this.residency = residency;
        this.claims = claims;
        this.sessions = sessions;
        this.clock = clock;
    }

    /** {@code mode} 是 prompt、follow-up 或 steer。载入失败的会话到重启前都返回 500。 */
    public Submission submit(Principal principal, SessionId session, RequestId request, String mode, String text) {
        if (residency.failure(session) != null) {
            return Submission.rejected(500);
        }
        SessionState existing = view.current().sessions().get(session);
        if (existing != null && !existing.principal().equals(principal)) {
            return Submission.rejected(404);
        }
        if (existing == null) {
            int owner = claims.claim(principal, session);
            if (owner != 200) {
                return Submission.rejected(owner);
            }
        }
        boolean needsNewRun = existing == null || existing.activeRun() == null;
        return publish(principal, session, request, needsNewRun, submission -> switch (mode) {
            case "steer" -> new SteerSubmitted(submission, request, text);
            case "follow-up" -> new FollowUpSubmitted(submission, request, text);
            default -> new UserPrompt(submission, request, new OwnerRef.OfSession(session), text);
        });
    }

    /**
     * 手动压缩。会话不存在或不是该用户的 → 404；正在运行、没有压缩设置或没有可压缩的内容 → 409。
     * 通过后发布 {@code CompactRequested}，内核在空闲会话上开一个特殊运行。{@code instructions} 可为 null。
     */
    public Submission compact(Principal principal, SessionId session, RequestId request, String instructions) {
        Optional<SessionState> found = sessions.find(principal, session);
        if (found.isEmpty()) {
            return Submission.rejected(404);
        }
        SessionState existing = found.get();
        if (existing.activeRun() != null || existing.context() == null || existing.context().compaction() == null
                || !Compactor.worthCompacting(existing, existing.context().system(), existing.context().tools())) {
            return Submission.rejected(409);
        }
        return publish(principal, session, request, true, submission -> new CompactRequested(submission, request, instructions));
    }

    /** 返回 HTTP 状态。取消不去重，也不占活跃运行名额。 */
    public int cancel(Principal principal, RunRef run) {
        ExternalGate.Verdict verdict = gate.admit(principal, false);
        if (verdict != ExternalGate.Verdict.ADMITTED) {
            return status(verdict);
        }
        if (!input.tryPublish(envelope(principal, run.session(), run.run(), new Cancel(new RequestId(UUID.randomUUID().toString()))))) {
            gate.release(principal);
            return 429;
        }
        return 200;
    }

    /** 事件走完管线后调用：外部事件归还名额。 */
    public void processed(EventEnvelope envelope) {
        switch (envelope.event()) {
            case Submitted submitted -> release(envelope.principal(), submitted.submission());
            case Cancel cancel -> gate.release(envelope.principal());
            default -> { }
        }
    }

    private Submission publish(Principal principal, SessionId session, RequestId request, boolean needsNewRun,
                               Function<SubmissionId, Event> event) {
        SubmissionLedger.Claim claim = ledger.claim(principal, request);
        if (!claim.isNew()) {
            return new Submission(200, claim.submission(), true);
        }
        ExternalGate.Verdict verdict = gate.admit(principal, needsNewRun);
        if (verdict != ExternalGate.Verdict.ADMITTED) {
            ledger.release(principal, request);
            return Submission.rejected(status(verdict));
        }
        if (needsNewRun) {
            newRuns.add(claim.submission());
        }
        if (!input.tryPublish(envelope(principal, session, null, event.apply(claim.submission())))) {
            release(principal, claim.submission());
            ledger.release(principal, request);
            return Submission.rejected(429);
        }
        return new Submission(200, claim.submission(), false);
    }

    private void release(Principal principal, SubmissionId submission) {
        gate.release(principal);
        if (newRuns.remove(submission)) {
            gate.releaseNewRun();
        }
    }

    private EventEnvelope envelope(Principal principal, SessionId session, RunId run, Event event) {
        return new EventEnvelope(EventEnvelope.UNASSIGNED, clock.instant(), principal, session, run, event);
    }

    private static int status(ExternalGate.Verdict verdict) {
        return verdict == ExternalGate.Verdict.HALTED ? 503 : 429;
    }
}
