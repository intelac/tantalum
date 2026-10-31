package tantalum.value.state;

import java.util.Objects;
import tantalum.value.id.CommandId;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolResultBlock;

/** 一批工具调用中的一个及其进度。 */
public record ToolSlot(ToolCallBlock call, Status status, boolean concurrencySafe,
                       CommandId command, ToolResultBlock result) {

    public enum Status {
        /** 被钩子拦截或参数无效，已有错误结果，不会发出。 */
        BLOCKED,
        PENDING,
        SENT,
        DONE
    }

    public ToolSlot {
        Objects.requireNonNull(call, "call");
        Objects.requireNonNull(status, "status");
    }

    public static ToolSlot pending(ToolCallBlock call, boolean concurrencySafe) {
        return new ToolSlot(call, Status.PENDING, concurrencySafe, null, null);
    }

    public static ToolSlot blocked(ToolCallBlock call, ToolResultBlock result) {
        return new ToolSlot(call, Status.BLOCKED, false, null, result);
    }

    public ToolSlot sent(CommandId sentAs) {
        return new ToolSlot(call, Status.SENT, concurrencySafe, sentAs, null);
    }

    public ToolSlot done(ToolResultBlock toolResult) {
        return new ToolSlot(call, Status.DONE, concurrencySafe, command, toolResult);
    }

    public boolean finished() {
        return status == Status.BLOCKED || status == Status.DONE;
    }
}
