package tantalum.value.event;

import java.util.List;
import java.util.Map;
import static tantalum.value.event.ContextAssembled.*;
import tantalum.value.event.ContextAssembled.*;
import tantalum.value.id.CommandId;
import tantalum.value.model.ToolSpec;
import tantalum.value.state.CompactionSettings;
import tantalum.value.state.ContextSection;

/** 测试里用的简短建法：主代码只留参数最全的构造函数，给它加字段时只改这里。 */
public final class ContextAssembledFixture {

    private ContextAssembledFixture() {}

    public static ContextAssembled of(CommandId command, String system, List<ToolSpec> tools, CompactionSettings compaction, List<ContextSection> sections) {
        return new ContextAssembled(command, system, tools, compaction, sections, null, null, Map.of(), null);
    }

    public static ContextAssembled of(CommandId command, String system, List<ToolSpec> tools, CompactionSettings compaction, List<ContextSection> sections, List<String> enabledTools, List<String> enabledPlugins) {
        return new ContextAssembled(command, system, tools, compaction, sections, enabledTools, enabledPlugins, Map.of(), null);
    }

    public static ContextAssembled of(CommandId command, String system, List<ToolSpec> tools, CompactionSettings compaction, List<ContextSection> sections, List<String> enabledTools, List<String> enabledPlugins, Map<String, String> toolSources) {
        return new ContextAssembled(command, system, tools, compaction, sections, enabledTools, enabledPlugins, toolSources, null);
    }
}
