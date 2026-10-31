package tantalum.value.event;

import tantalum.value.id.CommandId;

/** 命令的结果事件。 */
public interface ResultEvent extends Event {

    CommandId command();
}
