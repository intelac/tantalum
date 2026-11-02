package tantalum.shell.loop;

import tantalum.value.event.EventEnvelope;
import tantalum.value.state.KernelState;

/** 内核处理完一个事件后调用，拿到处理前后的状态。必须立即返回，不能阻塞。 */
@FunctionalInterface
public interface StepListener {

    StepListener NONE = (envelope, before, after) -> { };

    void onStep(EventEnvelope envelope, KernelState before, KernelState after);
}
