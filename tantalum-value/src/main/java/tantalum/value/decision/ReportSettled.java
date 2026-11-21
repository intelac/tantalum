package tantalum.value.decision;

import java.util.Objects;
import tantalum.value.assignment.AssignmentOutcome;
import tantalum.value.id.RunRef;

/**
 * 交代出去的一件事有了结果。{@code run} 是看到这个结果的那一轮（正在跑的，或者为它新开的）；{@code paused} 为真时
 * 自动开的轮数到了上限，结果只记进历史、没有开运行，等人说话。
 */
public record ReportSettled(RunRef run, String assignment, AssignmentOutcome outcome, boolean paused) implements Fact {
    public ReportSettled {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(assignment, "assignment");
        Objects.requireNonNull(outcome, "outcome");
    }

    @Override
    public String type() {
        return "ReportSettled";
    }
}
