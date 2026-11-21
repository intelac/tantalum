package tantalum.kernel.step;

import tantalum.value.assignment.AssignmentOutcome;
import tantalum.value.event.Root;
import tantalum.value.id.CommandId;
import tantalum.value.id.RunRef;
import tantalum.value.id.SessionId;
import tantalum.value.model.ModelChoice;

/**
 * 交代测试里看送达命令的三种样子（delivery 之前它们是三种命令）：{@link AssignmentFixture#commands} 把 {@code Deliver} 按送的东西
 * 换成这几个，测试照旧按“送出了一次交代 / 交回了结果 / 发了叫停”断言。
 */
final class AssignmentViews {

    private AssignmentViews() {}

    record Assign(CommandId id, RunRef run, String assignment, SessionId session, String agent, String prompt, String dir,
                  boolean offRecord, ModelChoice model, Root root) {}

    record ReturnReport(CommandId id, RunRef run, SessionId issuer, String assignment, AssignmentOutcome outcome, String text, Root root) {}

    record StopAssignment(CommandId id, RunRef run, String assignment, SessionId session) {}
}
