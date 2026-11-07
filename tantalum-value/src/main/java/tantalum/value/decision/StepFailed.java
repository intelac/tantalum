package tantalum.value.decision;

import java.util.Objects;
import tantalum.value.id.RunRef;

/**
 * 一步的处理函数抛了异常而没有运行可以结束（review-fixes 需求 5.1）：内核不能写日志，用事实上报，journal 和推送里都留痕。
 * {@code run} 是事件所在会话里按序号造的引用（没有真正的运行）；{@code failure} 只有异常类名，不带消息。
 */
public record StepFailed(RunRef run, String event, String failure) implements Fact {

    public StepFailed {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(failure, "failure");
    }

    @Override
    public String type() {
        return "StepFailed";
    }
}
