package tantalum.value.event;

import tantalum.value.id.CommandId;

/** 命令或其结果没能进入环。这违反了容量不变量，受影响的运行以失败结束。 */
public record CommandDropped(CommandId command, String detail) implements Event {

    public static final String TYPE = "CommandDropped";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public int version() {
        return 1;
    }
}
