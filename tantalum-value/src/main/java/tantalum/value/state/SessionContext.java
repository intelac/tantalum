package tantalum.value.state;

import java.util.List;
import java.util.Objects;
import tantalum.value.hook.Params;
import tantalum.value.json.Json;
import tantalum.value.model.HookTextBlock;
import tantalum.value.model.ToolSpec;
import tantalum.value.model.ToolUnlock;

/**
 * 会话第一次发给模型的 system 和工具，之后固定不变：thinking 签名绑定它们，一改旧块就失效。
 * {@code sections} 是最近告知模型的可变内容；{@code compaction} 是压缩设置，为空表示这个会话不压缩；每次提问时换成 shell 按用户当前选择算出的那份。
 * {@code tools} 是全集。{@code initialTools} 是会话开始时开着的工具，其余的发给模型时标为 deferred；{@code activeTools}、
 * {@code activePlugins} 是现在开着的工具和钩子插件。三者为 null 表示全部开着（旧会话）。
 * {@code hooks} 是钩子补丁的状态：说明和工具都以追加的 system 消息告诉模型，这里记下上次告诉过什么；
 * null 表示钩子还没追加过东西（旧会话、压缩后重新冻结）。
 * {@code pluginParams} 是最近一次组装时每个钩子插件的参数 {@code {插件: {键: 值}}}，null 表示没记过（旧会话、没有带参数的钩子插件）。
 * {@code unlocks} 是最近一次组装时按需出现的工具的规则（browser-on-demand），null 表示没有。
 */
public record SessionContext(String system, List<ToolSpec> tools, CompactionSettings compaction, List<ContextSection> sections,
                             List<String> initialTools, List<String> activeTools, List<String> activePlugins, HookState hooks,
                             Json.Obj pluginParams, List<ToolUnlock> unlocks) {

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
        unlocks = unlocks == null || unlocks.isEmpty() ? null : List.copyOf(unlocks);
    }

    public SessionContext(String system, List<ToolSpec> tools, CompactionSettings compaction, List<ContextSection> sections,
                          List<String> initialTools, List<String> activeTools, List<String> activePlugins, HookState hooks,
                          Json.Obj pluginParams) {
        this(system, tools, compaction, sections, initialTools, activeTools, activePlugins, hooks, pluginParams, null);
    }

    public SessionContext(String system, List<ToolSpec> tools, CompactionSettings compaction, List<ContextSection> sections,
                          List<String> initialTools, List<String> activeTools, List<String> activePlugins) {
        this(system, tools, compaction, sections, initialTools, activeTools, activePlugins, null, null);
    }

    /** 只换告知过模型的可变内容，system 和工具不动。 */
    public SessionContext withSections(List<ContextSection> newSections) {
        return new SessionContext(system, tools, compaction, newSections, initialTools, activeTools, activePlugins, hooks, pluginParams, unlocks);
    }

    /** 换压缩设置；不影响发给模型的内容。 */
    public SessionContext withCompaction(CompactionSettings newCompaction) {
        return new SessionContext(system, tools, newCompaction, sections, initialTools, activeTools, activePlugins, hooks, pluginParams, unlocks);
    }

    /** 换现在开着的工具和插件；工具全集和会话开始时的名单不动。 */
    public SessionContext withActive(List<String> tools, List<String> plugins) {
        return new SessionContext(system, this.tools, compaction, sections, initialTools, tools, plugins, hooks, pluginParams, unlocks);
    }

    /** 换钩子补丁的状态。 */
    public SessionContext withHooks(HookState newHooks) {
        return new SessionContext(system, tools, compaction, sections, initialTools, activeTools, activePlugins, newHooks, pluginParams, unlocks);
    }

    /** 换钩子插件的参数；不影响发给模型的内容。 */
    public SessionContext withPluginParams(Json.Obj params) {
        return new SessionContext(system, tools, compaction, sections, initialTools, activeTools, activePlugins, hooks, params, unlocks);
    }

    /** 换按需出现的工具的规则；不影响发给模型的内容。 */
    public SessionContext withUnlocks(List<ToolUnlock> newUnlocks) {
        return new SessionContext(system, tools, compaction, sections, initialTools, activeTools, activePlugins, hooks, pluginParams, newUnlocks);
    }

    /** 按需出现的工具的规则，没有时是空表。 */
    public List<ToolUnlock> unlockRules() {
        return unlocks == null ? List.of() : unlocks;
    }

    /** 这个插件的那一包参数；没记过时是 {@link Params#NONE}，钩子用自己构造时的值。 */
    public Params paramsOf(String plugin) {
        return pluginParams != null && pluginParams.fields().get(plugin) instanceof Json.Obj mine ? new Params(mine) : Params.NONE;
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
