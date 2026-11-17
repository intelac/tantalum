package tantalum.value.state;

import tantalum.value.id.CommandId;
import tantalum.value.id.SessionId;

/** 运行的所有者。用户发起的运行归会话所有；子运行归创建它的工具命令所有。 */
public sealed interface OwnerRef {

    record OfSession(SessionId session) implements OwnerRef {}

    record OfCommand(CommandId command) implements OwnerRef {}
}
