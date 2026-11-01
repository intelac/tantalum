package tantalum.value.decision;

import java.util.Objects;
import tantalum.value.id.RunRef;
import tantalum.value.model.TokenUsage;
import tantalum.value.state.Compacting;

/** 压缩成功。估算是压缩前后视图的估算 token 数；{@code usage} 是摘要调用的实测用量，可能为空；{@code trimOnly} 表示只裁剪、没有采用摘要。 */
public record ContextCompacted(RunRef run, Compacting.Trigger trigger, int headUnits, int keptUnits,
                               long estimateBefore, long estimateAfter, TokenUsage usage, boolean trimOnly) implements Fact {

    public ContextCompacted {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(trigger, "trigger");
    }

    @Override
    public String type() {
        return "ContextCompacted";
    }
}
