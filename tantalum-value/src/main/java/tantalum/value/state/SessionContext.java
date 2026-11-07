package tantalum.value.state;

import java.util.List;
import java.util.Objects;
import tantalum.value.model.HookTextBlock;
import tantalum.value.model.ToolSpec;

/**
 * 会话第一次发给模型的 system 和工具，之后固定不变：thinking 签名绑定它们，一改旧块就失效。
 * {@code sections} 是最近告知模型的可变内容；{@code compaction} 是压缩设置，为空表示这个会话不压缩；每次提问时换成 shell 按用户当前选择算出的那份。
 * {@code tools} 是全集。{@code initialTools} 是会话开始时开着的工具，其余的发给模型时标为 deferred；{@code activeTools}、
 * {@code activePlugins} 是现在开着的工具和钩子插件。三者为 null 表示全部开着（旧会话）。
 * {@code hooks} 是钩子补丁的状态：说明和工具都以追加的 system 消息告诉模型，这里记下上次告诉过什么；
 * null 表示钩子还没追加过东西（旧会话、压缩后重新冻结）。
 */
public record SessionContext(String system, List<ToolSpec> tools, CompactionSettings compaction, List<ContextSection> sections,
                             List<String> initialTools, List<String> activeTools, List<String> activePlugins, HookState hooks) {

    /**
     * 钩子补丁的状态。{@code texts} 是上次发给模型的每个钩子的说明（按注册顺序），按钩子比较变没变；
     * {@code tools} 是钩子加过的所有工具定义，按第一次出现的顺序只增不减，发请求时接在全集后面、永远 deferred；
     * {@code active} 是现在由钩子开着的工具名，用来算 {@code tool_addition}/{@code tool_removal}。
     */
    public record HookState(List<HookTextBlock> texts, List<HookTool> tools, List<String> active) {

        public static final HookState NONE = new HookState(List.of(), List.of(), List.of());

        /** 钩子加的一个工具：定义冻结（同名再出现沿用第一次的），谁加的记下来给轨迹页。 */
        public record HookTool(String hook, ToolSpec tool) {
            public HookTool {
                Objects.requireNonNull(hook, "hook");
                Objects.requireNonNull(tool, "tool");
            }
        }

        public HookState {
            texts = List.copyOf(texts);
            tools = List.copyOf(tools);
            active = List.copyOf(active);
        }
    }

    public SessionContext {
        Objects.requireNonNull(system, "system");
        tools = List.copyOf(tools);
        sections = List.copyOf(sections);
        initialTools = initialTools == null ? null : List.copyOf(initialTools);
        activeTools = activeTools == null ? null : List.copyOf(activeTools);
        activePlugins = activePlugins == null ? null : List.copyOf(activePlugins);
    }

    public SessionContext(String system, List<ToolSpec> tools, CompactionSettings compaction, List<ContextSection> sections) {
        this(system, tools, compaction, sections, null, null, null, null);
    }

    public SessionContext(String system, List<ToolSpec> tools, CompactionSettings compaction, List<ContextSection> sections,
                          List<String> initialTools, List<String> activeTools, List<String> activePlugins) {
        this(system, tools, compaction, sections, initialTools, activeTools, activePlugins, null);
    }

    /** 只换告知过模型的可变内容，system 和工具不动。 */
    public SessionContext withSections(List<ContextSection> newSections) {
        return new SessionContext(system, tools, compaction, newSections, initialTools, activeTools, activePlugins, hooks);
    }

    /** 换压缩设置；不影响发给模型的内容。 */
    public SessionContext withCompaction(CompactionSettings newCompaction) {
        return new SessionContext(system, tools, newCompaction, sections, initialTools, activeTools, activePlugins, hooks);
    }

    /** 换现在开着的工具和插件；工具全集和会话开始时的名单不动。 */
    public SessionContext withActive(List<String> tools, List<String> plugins) {
        return new SessionContext(system, this.tools, compaction, sections, initialTools, tools, plugins, hooks);
    }

    /** 换钩子补丁的状态。 */
    public SessionContext withHooks(HookState newHooks) {
        return new SessionContext(system, tools, compaction, sections, initialTools, activeTools, activePlugins, newHooks);
    }

    /** 钩子补丁的状态，没有时是 {@link HookState#NONE}。 */
    public HookState hookState() {
        return hooks == null ? HookState.NONE : hooks;
    }

    /** 这个工具现在开着吗。名单为 null 时全部开着。只看用户开关的工具，钩子加的工具不算。 */
    public boolean toolActive(String name) {
        return activeTools == null || activeTools.contains(name);
    }

    public boolean pluginActive(String plugin) {
        return activePlugins == null || activePlugins.contains(plugin);
    }
}
