package tantalum.value.event;

import tantalum.value.id.RequestId;
import tantalum.value.id.SubmissionId;

/** 用户要求压缩会话。{@code instructions} 是给摘要的附加说明，可以为 null。 */
public record CompactRequested(SubmissionId submission, RequestId request, String instructions) implements Event {

    public static final String TYPE = "CompactRequested";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public int version() {
        return 1;
    }
}
