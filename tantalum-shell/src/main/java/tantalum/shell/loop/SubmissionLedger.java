package tantalum.shell.loop;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import tantalum.value.event.Event;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.FollowUpSubmitted;
import tantalum.value.event.SteerSubmitted;
import tantalum.value.event.UserPrompt;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.SubmissionId;

/** 按 {@code (用户, requestId)} 原子去重。启动时由 journal 重建，所以重启后重复提交也不会重复执行。 */
public final class SubmissionLedger {

    /** 认领结果。{@code isNew} 为 false 时不要再提交。 */
    public record Claim(SubmissionId submission, boolean isNew) {}

    private record Key(Principal principal, RequestId request) {}

    private final Map<Key, SubmissionId> submissions = new ConcurrentHashMap<>();

    public Claim claim(Principal principal, RequestId request) {
        SubmissionId fresh = new SubmissionId(UUID.randomUUID().toString());
        SubmissionId existing = submissions.putIfAbsent(new Key(principal, request), fresh);
        return existing == null ? new Claim(fresh, true) : new Claim(existing, false);
    }

    /** 事件没能进入环时撤销认领，让调用方可以重试。 */
    public void release(Principal principal, RequestId request) {
        submissions.remove(new Key(principal, request));
    }

    public void rebuild(List<EventEnvelope> events) {
        for (EventEnvelope envelope : events) {
            Event event = envelope.event();
            switch (event) {
                case UserPrompt prompt -> put(envelope.principal(), prompt.request(), prompt.submission());
                case SteerSubmitted steer -> put(envelope.principal(), steer.request(), steer.submission());
                case FollowUpSubmitted followUp -> put(envelope.principal(), followUp.request(), followUp.submission());
                default -> { }
            }
        }
    }

    private void put(Principal principal, RequestId request, SubmissionId submission) {
        submissions.put(new Key(principal, request), submission);
    }
}
