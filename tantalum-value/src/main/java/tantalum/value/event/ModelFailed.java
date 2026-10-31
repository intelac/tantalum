package tantalum.value.event;

import java.time.Duration;
import tantalum.value.id.CommandId;
import tantalum.value.model.FailureReason;

/** 一次模型调用的结算：失败。部分内容只用于诊断，不进后续 prompt。 */
public record ModelFailed(CommandId command, FailureReason reason, boolean retryable, Duration retryAfter, String partialText, String detail) implements ResultEvent {

    public static final String TYPE = "ModelFailed";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public int version() {
        return 1;
    }
}
