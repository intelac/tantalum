package tantalum.value.state;

import tantalum.value.id.SessionId;

/** 运行的所有者。用户发起的运行归会话所有；为任务开的那一轮归放出它的会话 {@code parent} 的那个任务，结束时结果交回去。 */
public sealed interface OwnerRef {

    record OfSession(SessionId session) implements OwnerRef {}

    record OfTask(SessionId parent, String task) implements OwnerRef {}
}
