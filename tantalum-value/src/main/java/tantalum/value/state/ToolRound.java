package tantalum.value.state;

import java.util.ArrayList;
import java.util.List;
import tantalum.value.model.ToolResultBlock;

/** 模型一次回复里的全部工具调用，保持模型请求的顺序。{@code checked} 是已过 {@code beforeToolCall} 的调用数。 */
public record ToolRound(List<ToolSlot> slots, int checked) {

    public ToolRound {
        slots = List.copyOf(slots);
    }

    public ToolRound(List<ToolSlot> slots) {
        this(slots, slots.size());
    }

    public ToolRound withChecked(int newChecked) {
        return new ToolRound(slots, newChecked);
    }

    public boolean allFinished() {
        return slots.stream().allMatch(ToolSlot::finished);
    }

    public ToolRound withSlot(int index, ToolSlot slot) {
        List<ToolSlot> updated = new ArrayList<>(slots);
        updated.set(index, slot);
        return new ToolRound(updated, checked);
    }

    /** 已有的结果，按模型请求的顺序。 */
    public List<ToolResultBlock> results() {
        return slots.stream().filter(ToolSlot::finished).map(ToolSlot::result).toList();
    }
}
