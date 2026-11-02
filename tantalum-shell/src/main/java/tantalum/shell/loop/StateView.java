package tantalum.shell.loop;

import tantalum.value.state.KernelState;

/** 向查询方发布最新的不可变状态。 */
public final class StateView {

    private volatile KernelState current;

    public StateView() {
        this.current = KernelState.empty();
    }

    public KernelState current() {
        return current;
    }

    void publish(KernelState state) {
        current = state;
    }
}
