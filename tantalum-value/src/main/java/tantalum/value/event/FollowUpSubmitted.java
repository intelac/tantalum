package tantalum.value.event;

import tantalum.value.id.RequestId;
import tantalum.value.id.SubmissionId;

/** 排队提交，运行结束后开始下一轮。 */
public record FollowUpSubmitted(SubmissionId submission, RequestId request, String text) implements Event {

    public static final String TYPE = "FollowUpSubmitted";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public int version() {
        return 1;
    }
}
