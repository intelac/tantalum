package tantalum.value.event;

import java.util.List;
import java.util.Objects;
import tantalum.value.id.CommandId;
import tantalum.value.model.ToolSpec;
import tantalum.value.state.CompactionSettings;
import tantalum.value.state.ContextSection;

/**
 * 提示词组装完成：完整的 system prompt 和工具列表。{@code sections} 是 system 里会变的几段（共享知识、AGENTS.md、记忆索引），
 * 会话已冻结 system 时内核只把它们的变化追加给模型。{@code compaction} 是这次组装时的压缩设置，没配窗口时为 null，表示不压缩。
 */
public record ContextAssembled(CommandId command, String system, List<ToolSpec> tools, CompactionSettings compaction,
                               List<ContextSection> sections) implements ResultEvent {

    public static final String TYPE = "ContextAssembled";

    public ContextAssembled {
        Objects.requireNonNull(system, "system");
        tools = List.copyOf(tools);
        sections = List.copyOf(sections);
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public int version() {
        return 1;
    }
}
