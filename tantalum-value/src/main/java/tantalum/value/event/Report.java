package tantalum.value.event;

import tantalum.value.id.SessionId;

import java.util.Objects;
import tantalum.value.assignment.AssignmentOutcome;

/**
 * 交代出去的一件事有了结果，进交代它的会话。不是人说的话：只由送达（{@code Deliver}）放进来，{@code from} 是接了这件事的会话。
 * {@code text} 是最后的回答（完成时）或结束的详情，可以为 null。{@code root} 是那次交代的 {@code root}，原样带回。
 * 同一次交代的结果到第二次时名单里已经没有它，什么都不改。
 */
public record Report(String assignment, AssignmentOutcome outcome, String text, Root root, SessionId from) implements Input {

    public static final String TYPE = "Report";

    public Report {
        Objects.requireNonNull(assignment, "assignment");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(from, "from");
    }

    @Override
    public Kind kind() {
        return Kind.REPORT;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public int version() {
        return 1;
    }
}
