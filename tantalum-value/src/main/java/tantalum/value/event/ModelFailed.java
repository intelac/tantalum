package tantalum.value.event;

import java.time.Duration;
import java.time.Instant;
import tantalum.value.id.CommandId;
import tantalum.value.model.FailureReason;
import tantalum.value.model.RequestOptions;

/** 一次模型调用的结算：失败。部分内容只用于诊断，不进后续 prompt。计时和选项同 {@link ModelReplied}，没拿到名额时为 null。 */
public record ModelFailed(CommandId command, FailureReason reason, boolean retryable, Duration retryAfter, String partialText, String detail,
                          Instant startedAt, Instant firstTokenAt, RequestOptions options) implements ResultEvent {

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
