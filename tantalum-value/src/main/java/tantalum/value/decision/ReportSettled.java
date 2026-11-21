package tantalum.value.decision;

import java.util.Objects;
import tantalum.value.assignment.AssignmentOutcome;
import tantalum.value.id.RunRef;

/**
 * 交代出去的一件事有了结果。{@code run} 是看到这个结果的那一轮（正在跑的，或者为它新开的）；{@code paused} 为真时
 * 自动开的轮数到了上限，结果只记进历史、没有开运行，等人说话。{@code text} 是记进历史的结果文字（交回的回答、结束的详情，
 * 或送不过去时的原因，delivery），没有为 null：投影照它显示，不去猜是哪种事件带来的。
 */
public record ReportSettled(RunRef run, String assignment, AssignmentOutcome outcome, String text, boolean paused) implements Fact {
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
