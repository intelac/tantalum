package tantalum.value.event;

import java.util.List;
import tantalum.value.id.CommandId;
import tantalum.value.model.ToolSpec;
import tantalum.value.state.CompactionSettings;
import tantalum.value.state.ContextSection;

/**
 * 提示词组装完成，含完整 system prompt 和工具列表。{@code dynamic} 是 system 里会变的部分（共享知识、AGENTS.md、记忆索引）；
 * 会话已冻结 system 时，内核靠它判断要不要追加变更说明。旧事件和不区分的调用方为 null，表示不比较。
 * {@code compaction} 是这次组装时的压缩设置，会话第一次组装时冻结；旧事件和没配窗口时为 null，表示不压缩。{@code sections} 是分段的可变内容，内核只把变化追加给模型；旧事件为 null，退回整段比较。
 */
public record ContextAssembled(CommandId command, String system, List<ToolSpec> tools, String dynamic,
                               CompactionSettings compaction, List<ContextSection> sections) implements ResultEvent {

    public static final String TYPE = "ContextAssembled";

    public ContextAssembled(CommandId command, String system, List<ToolSpec> tools) {
        this(command, system, tools, null, null, null);
    }

    public ContextAssembled(CommandId command, String system, List<ToolSpec> tools, String dynamic) {
        this(command, system, tools, dynamic, null, null);
    }

    public ContextAssembled(CommandId command, String system, List<ToolSpec> tools, String dynamic, CompactionSettings compaction) {
        this(command, system, tools, dynamic, compaction, null);
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
