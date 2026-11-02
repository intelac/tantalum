package tantalum.kernel.tool;

import java.util.ArrayList;
import java.util.List;
import tantalum.value.state.ToolRound;
import tantalum.value.state.ToolSlot;

/** 按模型顺序决定下一批可发的工具：连续的并行安全调用同时发，独占调用单独发并构成屏障。 */
public final class ToolScheduler {

    private ToolScheduler() {}

    /** 返回现在可以发出的调用下标。 */
    public static List<Integer> next(ToolRound round, int maxParallelTools) {
        List<ToolSlot> slots = round.slots();
        List<Integer> ready = new ArrayList<>();
        int inFlight = (int) slots.stream().filter(slot -> slot.status() == ToolSlot.Status.SENT).count();

        for (int i = 0; i < round.checked(); i++) {
            ToolSlot slot = slots.get(i);
            if (slot.isFinished()) {
                continue;
            }
            if (slot.status() == ToolSlot.Status.SENT) {
                if (!slot.concurrencySafe()) {
                    return ready;
                }
                continue;
            }
            if (!slot.concurrencySafe()) {
                if (inFlight == 0) {
                    ready.add(i);
                }
                return ready;
            }
            if (inFlight >= maxParallelTools) {
                return ready;
            }
            ready.add(i);
            inFlight++;
        }
        return ready;
    }
}
