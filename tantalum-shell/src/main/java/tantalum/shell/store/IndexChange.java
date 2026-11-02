package tantalum.shell.store;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import tantalum.shell.conversation.SessionSummary;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.FollowUpSubmitted;
import tantalum.value.event.SteerSubmitted;
import tantalum.value.event.Submitted;
import tantalum.value.event.UserPrompt;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;
import tantalum.value.id.SubmissionId;
import tantalum.value.model.TokenUsage;
import tantalum.value.state.KernelState;
import tantalum.value.state.PrincipalState;
import tantalum.value.state.SessionState;

/** 一个事件带给索引库的变化。比较内核处理前后的状态得到，不做 I/O；实时和重建用同一份逻辑。 */
public record IndexChange(Principal principal, SessionId session, long seq, Instant at, String title, String runStatus,
                          RunId activeRun, Usage usage, Submission submission, boolean runEnded) {

    /** 一个事件带来的用量，{@code seq} 在同一用户内唯一。 */
    public record Usage(long seq, LocalDate day, TokenUsage amount) {}

    public record Submission(RequestId request, SubmissionId id) {}

    public static IndexChange of(EventEnvelope envelope, KernelState before, KernelState after) {
        String text = switch (envelope.event()) {
            case UserPrompt prompt -> prompt.text();
            case SteerSubmitted steer -> steer.text();
            case FollowUpSubmitted followUp -> followUp.text();
            default -> null;
        };
        Submission submission = envelope.event() instanceof Submitted submitted
                ? new Submission(submitted.request(), submitted.submission()) : null;
        RunId active = activeRun(after, envelope.session());
        RunId previous = activeRun(before, envelope.session());
        return new IndexChange(envelope.principal(), envelope.session(), envelope.seq(), envelope.at(),
                text == null ? "" : SessionSummary.titleOf(text),
                active == null ? SessionSummary.IDLE : after.runs().get(active).phase().name(), active,
                usage(envelope, before, after), submission, previous != null && !previous.equals(active));
    }

    private static RunId activeRun(KernelState state, SessionId session) {
        SessionState found = state.sessions().get(session);
        return found == null ? null : found.activeRun();
    }

    /** 只有状态变化才是真的：内核会忽略迟到的回复。日期规则同 {@code UsageProjection}。 */
    private static Usage usage(EventEnvelope envelope, KernelState before, KernelState after) {
        PrincipalState was = before.principals().get(envelope.principal());
        PrincipalState now = after.principals().get(envelope.principal());
        if (was == now || now == null) {
            return null;
        }
        LocalDate day = LocalDate.ofInstant(envelope.at(), ZoneOffset.UTC);
        TokenUsage amount = now.usageOn(day).minus(was == null ? TokenUsage.ZERO : was.usageOn(day));
        return amount.equals(TokenUsage.ZERO) ? null : new Usage(envelope.seq(), day, amount);
    }
}
