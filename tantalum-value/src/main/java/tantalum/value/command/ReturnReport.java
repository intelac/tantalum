package tantalum.value.command;

import java.util.Objects;
import tantalum.value.assignment.Outcome;
import tantalum.value.event.Root;
import tantalum.value.id.CommandId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;

/**
 * 一次交代做完了，把结果交回交代它的会话 {@code issuer}：处理器往那个会话放一条 {@code Report}。
 * {@code run} 是为这次交代开的那一轮；{@code root} 是那次交代的，原样带回。
 */
public record ReturnReport(CommandId id, RunRef run, SessionId issuer, String assignment, Outcome outcome, String text, Root root)
        implements Command {
    public ReturnReport {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(assignment, "assignment");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(root, "root");
    }
}
