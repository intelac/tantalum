package tantalum.value.event;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import tantalum.value.id.CommandId;
import tantalum.value.model.ModelChoice;
import tantalum.value.model.ToolSpec;
import tantalum.value.state.CompactionSettings;
import tantalum.value.state.ContextSection;

/**
 * 提示词组装完成：完整的 system prompt 和工具列表。{@code sections} 是 system 里会变的几段（共享知识、AGENTS.md、记忆索引），
 * 会话已冻结 system 时内核只把它们的变化追加给模型。{@code compaction} 是这次组装时的压缩设置，没配窗口时为 null，表示不压缩。
 * {@code tools} 是服务器上的全部工具；{@code enabledTools}、{@code enabledPlugins} 是这个用户当时开着的工具和钩子插件
 *，为 null 表示全部开着。{@code toolSources} 是每个工具从哪来：{@code builtin} 或 {@code mcp:<服务器名>}
 *，内核不读它，画面用来说明工具为什么不能用了。
 * {@code model} 是这次提问实际用的路线、模型和档位（“配上了什么”），旧事件为 null。
 */
public record ContextAssembled(CommandId command, String system, List<ToolSpec> tools, CompactionSettings compaction,
                               List<ContextSection> sections, List<String> enabledTools, List<String> enabledPlugins,
                               Map<String, String> toolSources, ModelChoice model)
        implements ResultEvent {

    public static final String TYPE = "ContextAssembled";

    public ContextAssembled {
        Objects.requireNonNull(system, "system");
        tools = List.copyOf(tools);
        sections = List.copyOf(sections);
        enabledTools = enabledTools == null ? null : List.copyOf(enabledTools);
        enabledPlugins = enabledPlugins == null ? null : List.copyOf(enabledPlugins);
        toolSources = toolSources == null ? Map.of() : Map.copyOf(toolSources);
    }

    /** 不记选择时（旧事件、测试）。 */
    public ContextAssembled(CommandId command, String system, List<ToolSpec> tools, CompactionSettings compaction,
                            List<ContextSection> sections, List<String> enabledTools, List<String> enabledPlugins,
                            Map<String, String> toolSources) {
        this(command, system, tools, compaction, sections, enabledTools, enabledPlugins, toolSources, null);
    }

    /** 不记工具来源时（测试）。 */
    public ContextAssembled(CommandId command, String system, List<ToolSpec> tools, CompactionSettings compaction,
                            List<ContextSection> sections, List<String> enabledTools, List<String> enabledPlugins) {
        this(command, system, tools, compaction, sections, enabledTools, enabledPlugins, Map.of(), null);
    }

    /** 不区分用户开关时（全部开着）。 */
    public ContextAssembled(CommandId command, String system, List<ToolSpec> tools, CompactionSettings compaction,
                            List<ContextSection> sections) {
        this(command, system, tools, compaction, sections, null, null, Map.of(), null);
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public int version() {
        return 2;
    }
}
