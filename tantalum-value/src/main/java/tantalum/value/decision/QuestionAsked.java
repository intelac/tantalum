package tantalum.value.decision;

import java.util.Objects;
import tantalum.value.id.RunRef;
import tantalum.value.question.Question;

/** 运行停下来问用户了。{@code askedBy} 是钩子名，或 {@code ask_user}。 */
public record QuestionAsked(RunRef run, String askedBy, Question question) implements Fact {

    public QuestionAsked {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(askedBy, "askedBy");
        Objects.requireNonNull(question, "question");
    }

    @Override
    public String type() {
        return "QuestionAsked";
    }
}
