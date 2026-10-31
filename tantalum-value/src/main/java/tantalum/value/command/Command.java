package tantalum.value.command;

import tantalum.value.id.CommandId;
import tantalum.value.id.RunRef;

/** 内核发出的命令。不是封闭类型，插件可以注册自己的命令。 */
public interface Command {

    CommandId id();

    RunRef run();
}
