package tantalum.value.event;

import tantalum.value.id.RequestId;
import tantalum.value.id.SubmissionId;

/** 运行中插话，在当前模型调用或工具轮之后加入。 */
public record SteerSubmitted(SubmissionId submission, RequestId request, String text) implements Submitted {

    public static final String TYPE = "SteerSubmitted";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public int version() {
        return 1;
    }
}
