package tantalum.value.event;

import java.util.Objects;
import tantalum.value.id.RequestId;
import tantalum.value.id.SubmissionId;
import tantalum.value.question.Answer;

/**
 * 用户回答了运行问的问题。和提问一样是提交（{@link Submitted}）：占一个入口名额、按请求 ID 去重、落盘后才回“已接受”。
 * {@code question} 是问题 id，不是当前问题的回答由内核忽略；{@code answer} 的合法性由内核按问题再验一次。
 */
public record Answered(SubmissionId submission, RequestId request, String question, Answer answer) implements Submitted {

    public static final String TYPE = "Answered";

    public Answered {
        Objects.requireNonNull(submission, "submission");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(question, "question");
        Objects.requireNonNull(answer, "answer");
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
