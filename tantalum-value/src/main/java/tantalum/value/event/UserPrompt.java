package tantalum.value.event;

import tantalum.value.id.RequestId;
import tantalum.value.id.SubmissionId;
import tantalum.value.state.OwnerRef;

/** 用户提问，也是运行的创建事件。 */
public record UserPrompt(SubmissionId submission, RequestId request, OwnerRef owner, String text) implements Submitted {

    public static final String TYPE = "UserPrompt";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public int version() {
        return 1;
    }
}
