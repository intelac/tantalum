package tantalum.value.event;

import java.time.Instant;
import java.util.Objects;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;

/** journal 的一行。{@code seq} 由写入方分配，写入前为 {@link #UNASSIGNED}；新运行的提交没有 {@code run}。 */
public record EventEnvelope(long seq, Instant at, Principal principal, SessionId session, RunId run, Event event) {

    public static final long UNASSIGNED = -1;

    public EventEnvelope {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(event, "event");
    }

    public EventEnvelope withSeq(long newSeq) {
        return new EventEnvelope(newSeq, at, principal, session, run, event);
    }
}
