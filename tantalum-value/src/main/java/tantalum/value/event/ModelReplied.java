package tantalum.value.event;

import java.util.List;
import tantalum.value.id.CommandId;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.StopReason;
import tantalum.value.model.TokenUsage;

/** 一次模型调用的结算：完整回复。用量可能为空，不估算。 */
public record ModelReplied(CommandId command, List<ContentBlock> content, StopReason stop, String actualModel, TokenUsage usage) implements ResultEvent {

    public static final String TYPE = "ModelReplied";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public int version() {
        return 1;
    }
}
