package tantalum.kernel.step;

import tantalum.value.id.CommandId;
import tantalum.value.id.SessionId;

/** 由事件序号和下标算出命令 ID，回放时得到相同的值。 */
public final class CommandIds {

    private CommandIds() {}

    public static CommandId of(SessionId session, long seq, int index) {
        return new CommandId(session.value() + "_" + seq + "-" + index);
    }
}
