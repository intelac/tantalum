package tantalum.value.state;

import java.util.List;
import java.util.Objects;
import tantalum.value.model.ToolSpec;

/**
 * 会话第一次发给模型的 system 和工具，之后固定不变：thinking 签名绑定它们，一改旧块就失效。
 * {@code sections} 是最近告知模型的可变内容；{@code compaction} 是压缩设置，为空表示这个会话不压缩；每次提问时换成 shell 按用户当前选择算出的那份（compaction-threshold T6）。
 * {@code tools} 是全集。{@code initialTools} 是会话开始时开着的工具，其余的发给模型时标为 deferred；{@code activeTools}、
 * {@code activePlugins} 是现在开着的工具和钩子插件（core-tools C14）。三者为 null 表示全部开着（旧会话）。
 */
public record SessionContext(String system, List<ToolSpec> tools, CompactionSettings compaction, List<ContextSection> sections,
                             List<String> initialTools, List<String> activeTools, List<String> activePlugins) {

    public SessionContext {
        Objects.requireNonNull(system, "system");
        tools = List.copyOf(tools);
        sections = List.copyOf(sections);
        initialTools = initialTools == null ? null : List.copyOf(initialTools);
        activeTools = activeTools == null ? null : List.copyOf(activeTools);
        activePlugins = activePlugins == null ? null : List.copyOf(activePlugins);
    }

    public SessionContext(String system, List<ToolSpec> tools, CompactionSettings compaction, List<ContextSection> sections) {
        this(system, tools, compaction, sections, null, null, null);
    }

    /** 只换告知过模型的可变内容，system 和工具不动。 */
    public SessionContext withSections(List<ContextSection> newSections) {
        return new SessionContext(system, tools, compaction, newSections, initialTools, activeTools, activePlugins);
    }

    /** 换压缩设置（compaction-threshold T6）；不影响发给模型的内容。 */
    public SessionContext withCompaction(CompactionSettings newCompaction) {
        return new SessionContext(system, tools, newCompaction, sections, initialTools, activeTools, activePlugins);
    }

    /** 换现在开着的工具和插件；工具全集和会话开始时的名单不动。 */
    public SessionContext withActive(List<String> tools, List<String> plugins) {
        return new SessionContext(system, this.tools, compaction, sections, initialTools, tools, plugins);
    }

    /** 这个工具现在开着吗。名单为 null 时全部开着。 */
    public boolean toolActive(String name) {
        return activeTools == null || activeTools.contains(name);
    }

    public boolean pluginActive(String plugin) {
        return activePlugins == null || activePlugins.contains(plugin);
    }
}
