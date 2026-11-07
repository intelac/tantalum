package tantalum.value.decision;

import java.util.Objects;
import tantalum.value.id.RunRef;
import tantalum.value.question.Answer;

/** 问题有了结果：回答了（{@code answer} 非空）、期限到了、运行被取消。 */
public record QuestionSettled(RunRef run, String question, Answer answer, Reason reason) implements Fact {

    public enum Reason { ANSWERED, EXPIRED, CANCELLED }

    public QuestionSettled {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(question, "question");
        Objects.requireNonNull(reason, "reason");
        if ((answer != null) != (reason == Reason.ANSWERED)) {
            throw new IllegalArgumentException("answer is present exactly when the reason is ANSWERED");
        }
    }

    @Override
    public String type() {
        return "QuestionSettled";
    }
}
