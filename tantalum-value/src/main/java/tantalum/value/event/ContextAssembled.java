package tantalum.value.event;

import java.util.List;
import tantalum.value.id.CommandId;
import tantalum.value.model.ToolSpec;

/** 提示词组装完成，含完整 system prompt 和工具列表。 */
public record ContextAssembled(CommandId command, String system, List<ToolSpec> tools) implements ResultEvent {

    public static final String TYPE = "ContextAssembled";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public int version() {
        return 1;
    }
}
