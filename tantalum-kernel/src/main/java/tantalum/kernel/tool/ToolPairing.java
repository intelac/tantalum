package tantalum.kernel.tool;

import java.util.List;
import tantalum.value.model.ToolResultBlock;
import tantalum.value.state.ToolRound;
import tantalum.value.state.ToolSlot;

/** 运行提前结束时，为没有结果的工具调用生成错误结果，使 prompt 中调用和结果一一配对。 */
public final class ToolPairing {

    public static final String ABORTED_BEFORE_DISPATCH = "ABORTED_BEFORE_DISPATCH";
    public static final String OUTCOME_UNKNOWN = "TOOL_OUTCOME_UNKNOWN";

    private ToolPairing() {}

    public static List<ToolResultBlock> complete(ToolRound round) {
        return round.slots().stream().map(ToolPairing::resultOf).toList();
    }

    private static ToolResultBlock resultOf(ToolSlot slot) {
        if (slot.isFinished()) {
            return slot.result();
        }
        String id = slot.call().id();
        if (slot.status() == ToolSlot.Status.SENT) {
            return new ToolResultBlock(id,
                    "The tool may or may not have run; its outcome is unknown. Retry only read-only or idempotent "
                            + "operations. Otherwise check the external state or ask the user first.",
                    true, OUTCOME_UNKNOWN);
        }
        return new ToolResultBlock(id, "The run ended before this tool call was sent. It did not run.",
                true, ABORTED_BEFORE_DISPATCH);
    }
}
