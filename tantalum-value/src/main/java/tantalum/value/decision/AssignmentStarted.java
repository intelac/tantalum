package tantalum.value.decision;

import java.util.Objects;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;

/** 放出了一次交代。{@code run} 是放出它的那一轮，{@code session} 是接它的会话，{@code brief} 是交代的第一行。 */
public record AssignmentStarted(RunRef run, String assignment, SessionId session, String agent, String brief, String toolCallId)
        implements Fact {
    public AssignmentStarted {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(assignment, "assignment");
        Objects.requireNonNull(session, "session");
    }

    @Override
    public String type() {
        return "AssignmentStarted";
    }
}
