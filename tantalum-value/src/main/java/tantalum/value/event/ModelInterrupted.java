package tantalum.value.event;

import tantalum.value.id.CommandId;

/** 回复被取消时已收到的文本。不改运行状态，只追加到会话消息。 */
public record ModelInterrupted(CommandId command, String text) implements ResultEvent {

    public static final String TYPE = "ModelInterrupted";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public int version() {
        return 1;
    }
}
