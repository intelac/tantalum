package tantalum.value.event;

import tantalum.value.id.CommandId;

/** 一次工具调用的结果，失败也算结果。 */
public record ToolFinished(CommandId command, String toolCallId, String text, boolean error) implements ResultEvent {

    public static final String TYPE = "ToolFinished";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public int version() {
        return 1;
    }
}
