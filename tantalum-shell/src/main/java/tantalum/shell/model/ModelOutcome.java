package tantalum.shell.model;

import java.time.Duration;
import java.util.List;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.FailureReason;
import tantalum.value.model.StopReason;
import tantalum.value.model.TokenUsage;

/** 一次模型调用的结算结果。 */
public sealed interface ModelOutcome {

    record Replied(List<ContentBlock> content, StopReason stop, String actualModel, TokenUsage usage)
            implements ModelOutcome {}

    record Failed(FailureReason reason, boolean retryable, Duration retryAfter, String partialText, String detail)
            implements ModelOutcome {}

    /** 调用被中断，{@code text} 是已经发给用户的文本。 */
    record Interrupted(String text) implements ModelOutcome {}
}
