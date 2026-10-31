package tantalum.value.event;

import tantalum.value.id.CommandId;
import tantalum.value.json.Json;

/** 插件工作完成。{@code continueText} 有值时，它作为用户消息追加，然后回到模型。 */
public record WorkDone(CommandId command, String workType, Json result, boolean failed, String continueText) implements ResultEvent {

    public static final String TYPE = "WorkDone";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public int version() {
        return 1;
    }
}
