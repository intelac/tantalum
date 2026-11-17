package tantalum.value.decision;

import java.util.Objects;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;

/** 放出了一个任务。{@code run} 是放出它的那一轮，{@code session} 是为它开的会话，{@code brief} 是交代的第一行。 */
public record TaskStarted(RunRef run, String task, SessionId session, String agent, String brief, String toolCallId) implements Fact {

    public TaskStarted {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(session, "session");
    }

    @Override
    public String type() {
        return "TaskStarted";
    }
}
