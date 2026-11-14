package tantalum.shell.access;

import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.kernel.hook.KernelHook;
import tantalum.shell.param.ParamResolver;
import tantalum.shell.param.ParamResolver.Resolved;
import tantalum.shell.param.ParamRules;
import tantalum.shell.param.ParamRules.InvalidParam;
import tantalum.shell.param.ParamStore;
import tantalum.shell.param.ParamStore.Scope;
import tantalum.shell.param.SecretBox;
import tantalum.shell.plugin.AfterToolHook;
import tantalum.shell.plugin.Configurable;
import tantalum.shell.plugin.DailyLimit;
import tantalum.shell.plugin.Kind;
import tantalum.shell.plugin.Param;
import tantalum.shell.plugin.PluginInfo;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.ToolOutput;
import tantalum.shell.plugin.WorkResult;
import tantalum.shell.plugin.WorkType;
import tantalum.shell.tool.ToolCatalog;
import tantalum.value.hook.HookContext;
import tantalum.value.hook.HookPoint;
import tantalum.value.id.Principal;
import tantalum.value.hook.Params;
import tantalum.value.json.Json;
import tantalum.value.state.KernelState;
import tantalum.value.text.Failures;
import tantalum.value.text.LocalText;

/**
 * 插件的唯一入口：组装上下文时给出这个用户开着的工具和钩子插件；执行工具、工具后处理和插件工作前再按当前开关查一次。
 * 除了这里，没有代码调用 {@link Tool#run}、{@link AfterToolHook#afterToolExecution}、{@link WorkType#run}（ArchUnit 规则钉住）。
 * 内核钩子在内核里运行，由 {@code HookChain} 按会话记下的插件名单过滤。
 *
 * <p>插件的参数也从这里走：声明在启动时并好，值在调用时现取（改完下一次调用就生效），保存前整份检查。
 * 开关有三种锁（{@link Lock}）：代码里定的、服务器配置里定的、管理员在页面上锁的；锁着的开关用户改不了，自己填的普通参数也不算。
 */
public final class PluginGate {

    private static final Logger LOG = LoggerFactory.getLogger(PluginGate.class);

    public static final String TOOL_OFF = "This tool was turned off in the plugin page and is not available.";
    /** 钩子位置对应的回调名，从 {@link HookPoint} 派生：加一个位置不用再改这里。 */
    static final List<String> HOOK_POINTS = Arrays.stream(HookPoint.values()).map(PluginGate::callback).toList();

    /**
     * 一个钩子插件：{@link KernelHook#plugin()} 等返回同一个名字的钩子、工具后处理和插件工作，在插件页上是一项。
     * {@code mandatory} 是钩子声明的或管理员配置的必须开启；{@code defaultEnabled} 是用户没设置过时开不开。
     */
    public record Plugin(String name, LocalText description, List<String> points, boolean mandatory, boolean defaultEnabled) {}

    private final ToolCatalog catalog;
    private final PluginSwitches switches;
    private final List<ToolGroup> groups;
    private final List<AfterToolHook> afterHooks;
    private final List<WorkType> workTypes;
    private final Map<String, Plugin> plugins;
    /** 成员自己声明必须开启的插件（{@link PluginInfo#mandatory()}）；其余必须开启的是服务器配置定的。 */
    private final Set<String> lockedInCode;
    /** 插件给模型的固定说明（{@link PluginInfo#modelNote()}），按插件名。 */
    private final Map<String, String> modelNotes;
    /** 钩子插件声明的参数，没声明的不在里面。 */
    private final Map<String, List<Param>> pluginParams;
    /** 有内核钩子的插件：只有它们的参数记进组装上下文。 */
    private final Set<String> kernelPlugins;
    /** 报每日上限的成员，按插件分。 */
    private final Map<String, List<DailyLimit>> dailyLimits;
    private final ParamStore store;
    private final ParamResolver resolver;

    /** 没有本机工作区、联网这些开关组时（嵌入使用和测试）。 */
    public PluginGate(ToolCatalog catalog, PluginSwitches switches, List<KernelHook> hooks,
                      List<AfterToolHook> afterHooks, List<WorkType> workTypes) {
        this(catalog, switches, List.of(), hooks, afterHooks, workTypes, Set.of());
    }

    /** 参数不落盘（嵌入使用和测试）。 */
    public PluginGate(ToolCatalog catalog, PluginSwitches switches, List<ToolGroup> groups,
                      List<KernelHook> hooks, List<AfterToolHook> afterHooks, List<WorkType> workTypes, Set<String> required) {
        this(catalog, switches, groups, hooks, afterHooks, workTypes, required, unsavedParams());
    }

    /**
     * {@code groups} 是本机工作区、联网这些开关组：
     * 组里的工具不看单个工具的开关，只问自己的组。{@code required} 是管理员设成必须开启的插件
     * （{@code tantalum.hooks.required}），名字不认识时抛 {@link IllegalArgumentException}：拼错了等于策略没生效，不能悄悄放过。
     * {@code params} 是参数的值和密码存的地方。同一个插件里两个成员把同一个键声明得不一样，也抛 {@link IllegalArgumentException}。
     */
    public PluginGate(ToolCatalog catalog, PluginSwitches switches, List<ToolGroup> groups,
                      List<KernelHook> hooks, List<AfterToolHook> afterHooks, List<WorkType> workTypes, Set<String> required,
                      ParamStore params) {
        if (groups.stream().map(ToolGroup::kind).distinct().count() != groups.size()) {
            throw new IllegalArgumentException("two tool groups share one switch kind");
        }
        for (ToolGroup group : groups) {
            // 逐个勾的工具和开关存在同一种里，名字就是键：重了会互相盖掉
            List<String> picked = group.choices().values().stream().flatMap(List::stream).toList();
            if (picked.stream().anyMatch(group.switches()::contains) || picked.stream().distinct().count() != picked.size()) {
                throw new IllegalArgumentException("a tool that can be picked one by one shares its name with a switch or another tool: " + picked);
            }
        }
        this.catalog = catalog;
        this.switches = switches;
        this.groups = List.copyOf(groups);
        this.afterHooks = List.copyOf(afterHooks);
        this.workTypes = List.copyOf(workTypes);
        this.plugins = group(hooks, afterHooks, workTypes, required);
        this.lockedInCode = lockedInCode(hooks, afterHooks);
        this.modelNotes = modelNotes(hooks, afterHooks);
        this.pluginParams = declared(hooks, afterHooks, workTypes);
        this.kernelPlugins = hooks.stream().map(KernelHook::plugin).collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.dailyLimits = dailyLimits(hooks, afterHooks, workTypes);
        this.store = params;
        this.resolver = new ParamResolver(params);
        for (Tool tool : catalog.tools()) {
            // 现在就算一遍：声明有毛病的启动时就失败，不等到第一次调用
            if (toolParams(tool).isEmpty() && tool instanceof Configurable configurable
                    && !configurable.params().isEmpty()) {
                LOG.warn("tool {} declares params, but only built-in tools with their own switch can have them; ignored", tool.name());
            }
        }
    }

    private static ParamStore unsavedParams() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        try {
            return ParamStore.inMemory(Clock.systemUTC(), new SecretBox(key));
        } catch (SQLException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** 没有用户开关的入口：一切都开着（嵌入使用和测试）。仍然是唯一的调用入口。 */
    public static PluginGate allOn(ToolCatalog catalog, List<KernelHook> hooks, List<AfterToolHook> afterHooks, List<WorkType> workTypes) {
        try {
            return new PluginGate(catalog, PluginSwitches.inMemory(Clock.systemUTC()), hooks, afterHooks, workTypes);
        } catch (SQLException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    // ---- 名单 ----

    /** 内置工具看自己的开关；本机工作区、联网的工具问自己的 {@link ToolGroup}。 */
    public boolean toolEnabled(Principal user, Tool tool) {
        Optional<ToolGroup> group = groupOf(tool);
        if (group.isPresent()) {
            return group.get().enabled(user, tool);
        }
        return switches.enabled(user, Kind.TOOL, tool.name());
    }

    /**
     * 这个工具从哪来（写进 {@code ContextAssembled.toolSources}）：开关组的写组的来源（{@code local}、
     * {@code web}），其余写 {@code builtin}；不写地址、令牌、电脑名和目录。旧会话里还有 {@code mcp:<服务器>}（remove-generic-mcp 之前）。
     */
    public String source(Tool tool) {
        return groupOf(tool).map(ToolGroup::source)
                .orElse("builtin");
    }

    private Optional<ToolGroup> groupOf(Tool tool) {
        for (ToolGroup group : groups) {
            if (group.owns(tool)) {
                return Optional.of(group);
            }
        }
        return Optional.empty();
    }

    /** 这个用户现在开着的工具名，按名称排序（同 {@link ToolCatalog#specs()}）。 */
    public List<String> enabledTools(Principal user) {
        return catalog.tools().stream().filter(tool -> toolEnabled(user, tool)).map(Tool::name).toList();
    }

    /** 装没装这个插件（钩子、工具后处理、插件工作）。 */
    public boolean hasPlugin(String plugin) {
        return plugins.containsKey(plugin);
    }

    public boolean pluginEnabled(Principal user, String plugin) {
        Plugin known = plugins.get(plugin);
        return known != null && (known.mandatory() || switches.enabled(user, Kind.PLUGIN, plugin, known.defaultEnabled()));
    }

    public List<String> enabledPlugins(Principal user) {
        return plugins.keySet().stream().filter(name -> pluginEnabled(user, name)).toList();
    }

    /** 这个用户开着的插件给模型的说明，按插件名排序；一个都没有时为空。QAgent 问题 10：模型不知道改文件要批准。 */
    public List<String> modelNotes(Principal user) {
        return modelNotes.entrySet().stream().filter(entry -> pluginEnabled(user, entry.getKey()))
                .sorted(Map.Entry.comparingByKey()).map(Map.Entry::getValue).toList();
    }

    /**
     * 这个用户此刻的钩子插件参数 {@code {插件: {键: 值}}}，记进组装上下文，内核钩子在整个运行里用这一份（回放时也是）。
     * 只收开着的、有内核钩子的、有非密码参数的插件；插件按名字排序，键按声明顺序；一个都没有返回 null。
     * 工具后处理和插件工作在 shell 里跑，调用时现取，不走这里。
     */
    public Json.Obj pluginParams(Principal user) {
        Map<String, Json> byPlugin = new java.util.TreeMap<>();
        pluginParams.forEach((plugin, declared) -> {
            if (!kernelPlugins.contains(plugin) || !pluginEnabled(user, plugin)) {
                return;
            }
            Params mine = resolver.params(user, Kind.PLUGIN, plugin, declared, lock(Kind.PLUGIN, plugin) != null);
            if (!mine.values().fields().isEmpty()) {
                byPlugin.put(plugin, mine.values());
            }
        });
        return byPlugin.isEmpty() ? null : new Json.Obj(byPlugin);
    }

    /**
     * 这个人今天的 token 上限：他开着的插件里报了上限（{@link DailyLimit}）的，各按他此刻生效的参数问一次，取最小的；
     * 没有插件报就是空。只给页面显示用，拦截是插件钩子的事。
     */
    public OptionalLong dailyLimit(Principal user) {
        OptionalLong lowest = OptionalLong.empty();
        for (Map.Entry<String, List<DailyLimit>> entry : dailyLimits.entrySet()) {
            String plugin = entry.getKey();
            if (!pluginEnabled(user, plugin)) {
                continue;
            }
            Params mine = resolver.params(user, Kind.PLUGIN, plugin, pluginParams.getOrDefault(plugin, List.of()), lock(Kind.PLUGIN, plugin) != null);
            for (DailyLimit member : entry.getValue()) {
                OptionalLong limit = member.dailyTokens(mine);
                if (limit.isPresent() && (lowest.isEmpty() || limit.getAsLong() < lowest.getAsLong())) {
                    lowest = limit;
                }
            }
        }
        return lowest;
    }

    /** 运维页能直接改的那个上限：哪个插件的哪个参数，所有人的默认现在是多少（管理员定的，没定就是插件自带的）。 */
    public record LimitSource(String plugin, Param param, long value) {}

    /**
     * 报上限的插件里，第一个（按名字）说了“上限是哪个参数”（{@link DailyLimit#limitParam}）而且那个参数是归管理员的数字参数的。
     * 没有就是空：运维页不显示上限那一列以外的修改入口。
     */
    public Optional<LimitSource> limitSource() {
        for (String plugin : new java.util.TreeSet<>(dailyLimits.keySet())) {
            List<Param> declared = pluginParams.getOrDefault(plugin, List.of());
            for (DailyLimit member : dailyLimits.get(plugin)) {
                Optional<Param> param = member.limitParam().flatMap(key -> declared.stream().filter(each -> each.key().equals(key)
                        && each.type() == Param.Type.NUMBER && each.owner() == Param.Owner.ADMIN).findFirst());
                if (param.isPresent()) {
                    long value = resolver.defaults(Kind.PLUGIN, plugin, declared).stream()
                            .filter(item -> item.param().key().equals(param.get().key()))
                            .map(item -> ((Json.Num) item.value()).value().longValue()).findFirst().orElseThrow();
                    return Optional.of(new LimitSource(plugin, param.get(), value));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * 一个人的上限给运维页看：{@code reported} 有没有任何插件报上限；{@code enabled} 他开没开其中的哪一个；
     * {@code tokens} 同 {@link #dailyLimit}；{@code own} 是 {@link #limitSource} 那个参数给他单独设过。
     */
    public record UserLimit(boolean reported, boolean enabled, OptionalLong tokens, boolean own) {}

    public UserLimit limitOf(Principal user) {
        boolean enabled = dailyLimits.keySet().stream().anyMatch(plugin -> pluginEnabled(user, plugin));
        boolean own = limitSource().map(source -> resolver.resolve(user, Kind.PLUGIN, source.plugin(),
                        pluginParams.getOrDefault(source.plugin(), List.of())).stream()
                .anyMatch(item -> item.param().key().equals(source.param().key()) && item.source() == ParamResolver.Source.ASSIGNED))
                .orElse(false);
        return new UserLimit(!dailyLimits.isEmpty(), enabled, dailyLimit(user), own);
    }

    public List<Plugin> plugins() {
        return List.copyOf(plugins.values());
    }

    /** 注册了的工作类型，{@code WorkHandler} 按名字找。 */
    public List<WorkType> workTypes() {
        return workTypes;
    }

    // ---- 插件页 ----

    /**
     * 开关为什么改不了。{@code source}：{@code code} 成员自己声明必须开启，{@code config} 写在 {@code tantalum.hooks.required}，
     * {@code admin} 管理员在页面上锁的（只有这一种带 {@code reason}，也只有这一种能在页面上解开）。
     */
    public record Lock(String source, String reason) {
        public static final String CODE = "code";
        public static final String CONFIG = "config";
        public static final String ADMIN = "admin";
    }

    /**
     * 页面上的一个参数。普通参数有 {@code value} 和 {@code source}（{@code builtin}、{@code admin}、{@code own}、{@code assigned}）；
     * 密码两样都没有（为 null），只有 {@code set}（存着）和 {@code stale}（存着但解不开，要重新填）。{@code editable} 是看的人能不能改。
     */
    public record ParamView(Param param, Json value, String source, boolean editable, boolean set, boolean stale) {}

    /**
     * 一个开关在插件页上的样子：{@code byDefault} 是没动过时开不开（管理员定的默认算在内），
     * {@code changed} 是这个人选的和默认不一样，{@code lock} 没锁时为 null。
     */
    public record ToolView(String name, String description, boolean enabled, boolean byDefault, boolean changed, Lock lock,
                           List<ParamView> params) {}

    /** {@code group}：挂点全是工具后处理的是 {@code output}，其余是 {@code rule}。 */
    public record PluginView(String name, LocalText description, List<String> points, String group, boolean enabled, boolean byDefault,
                             boolean changed, Lock lock, List<ParamView> params) {}

    public record Overview(List<ToolView> tools, List<PluginView> plugins) {}

    /** 这个用户的插件页：内置工具、钩子插件。开关组（本机工作区、联网）的工具不在这里，在各自的卡片上。 */
    public Overview overview(Principal user) {
        List<ToolView> builtins = new ArrayList<>();
        for (Tool tool : catalog.tools()) {
            if (groupOf(tool).isPresent()) {
                continue;
            }
            Lock lock = lock(Kind.TOOL, tool.name());
            ToolView view = new ToolView(tool.name(), tool.description(), switches.enabled(user, Kind.TOOL, tool.name()),
                    byDefault(Kind.TOOL, tool.name()), changed(user, Kind.TOOL, tool.name()), lock,
                    paramViews(user, Kind.TOOL, tool.name(), toolParams(tool), lock != null));
            builtins.add(view);
        }
        List<PluginView> views = plugins.values().stream().map(plugin -> {
            Lock lock = lock(Kind.PLUGIN, plugin.name());
            return new PluginView(plugin.name(), plugin.description(), plugin.points(), group(plugin),
                    pluginEnabled(user, plugin.name()), byDefault(Kind.PLUGIN, plugin.name()), changed(user, Kind.PLUGIN, plugin.name()),
                    lock, paramViews(user, Kind.PLUGIN, plugin.name(), declared(Kind.PLUGIN, plugin.name()), lock != null));
        }).toList();
        return new Overview(List.copyOf(builtins), views);
    }

    /**
     * 管理员的“插件默认值”里的一项。{@code builtin} 是自带的默认，{@code byAdmin} 是管理员定的默认（没定为 null），
     * {@code lock} 同插件页；{@code params} 是参数的默认值，不含密码。{@code group}：{@code tool}、{@code rule}、{@code output}。
     */
    public record DefaultView(Kind kind, String name, String group, boolean builtin, Boolean byAdmin, Lock lock, List<ParamView> params) {}

    /** 所有能定默认的开关：内置工具、钩子插件。开关组不在这里。 */
    public List<DefaultView> defaults() {
        List<DefaultView> views = new ArrayList<>();
        for (Tool tool : catalog.tools()) {
            if (groupOf(tool).isEmpty()) {
                views.add(defaultView(Kind.TOOL, tool.name(), "tool", true, toolParams(tool)));
            }
        }
        plugins.values().forEach(plugin -> views.add(defaultView(Kind.PLUGIN, plugin.name(), group(plugin),
                plugin.mandatory() || plugin.defaultEnabled(), declared(Kind.PLUGIN, plugin.name()))));
        return List.copyOf(views);
    }

    private DefaultView defaultView(Kind kind, String name, String group, boolean builtin, List<Param> declared) {
        List<ParamView> params = resolver.defaults(kind, name, declared).stream()
                .map(item -> new ParamView(item.param(), item.value(), source(item), true, false, false)).toList();
        return new DefaultView(kind, name, group, builtin, switches.policy(kind, name).map(PluginSwitches.Policy::enabled).orElse(null),
                lock(kind, name), params);
    }

    /** 管理员替一个人定的参数：只有归管理员的那些，按这个人此刻的生效值。没有这种参数的插件不在里面。 */
    public record AssignedView(Kind kind, String name, List<ParamView> params) {}

    public List<AssignedView> assigned(Principal user) {
        List<AssignedView> views = new ArrayList<>();
        for (Tool tool : catalog.tools()) {
            assignedView(user, Kind.TOOL, tool.name(), toolParams(tool)).ifPresent(views::add);
        }
        pluginParams.forEach((name, declared) -> assignedView(user, Kind.PLUGIN, name, declared).ifPresent(views::add));
        return List.copyOf(views);
    }

    private Optional<AssignedView> assignedView(Principal user, Kind kind, String name, List<Param> declared) {
        List<ParamView> params = resolver.resolve(user, kind, name, declared, lock(kind, name) != null).stream()
                .filter(item -> item.param().owner() == Param.Owner.ADMIN)
                .map(item -> new ParamView(item.param(), item.value(), source(item), true, false, false)).toList();
        return params.isEmpty() ? Optional.empty() : Optional.of(new AssignedView(kind, name, params));
    }

    /** 按声明的顺序，密码也在里面（只有存没存、解不解得开）。 */
    private List<ParamView> paramViews(Principal user, Kind kind, String name, List<Param> declared, boolean locked) {
        if (declared.isEmpty()) {
            return List.of();
        }
        Map<String, Resolved> resolved = new LinkedHashMap<>();
        resolver.resolve(user, kind, name, declared, locked).forEach(item -> resolved.put(item.param().key(), item));
        List<ParamView> views = new ArrayList<>();
        for (Param param : declared) {
            if (param.secret()) {
                views.add(new ParamView(param, null, null, true, store.hasSecret(user, kind, name, param.key()),
                        store.stale(user, kind, name, param.key())));
            } else {
                Resolved item = resolved.get(param.key());
                views.add(new ParamView(param, item.value(), source(item), param.owner() == Param.Owner.USER && !locked, false, false));
            }
        }
        return List.copyOf(views);
    }

    private static String source(Resolved item) {
        return item.source().name().toLowerCase(Locale.ROOT);
    }

    private static String group(Plugin plugin) {
        return plugin.points().stream().allMatch("afterToolExecution"::equals) ? "output" : "rule";
    }

    // ---- 锁和默认 ----

    /** 这个开关锁着吗，没锁为 null。代码和配置定的排在管理员的前面：管理员在页面上解不开它们。 */
    public Lock lock(Kind kind, String name) {
        if (kind == Kind.PLUGIN) {
            Plugin plugin = plugins.get(name);
            if (plugin != null && plugin.mandatory()) {
                return new Lock(lockedInCode.contains(name) ? Lock.CODE : Lock.CONFIG, "");
            }
        }
        return switches.policy(kind, name).filter(PluginSwitches.Policy::locked)
                .map(policy -> new Lock(Lock.ADMIN, policy.reason())).orElse(null);
    }

    /** 没动过时开不开：必须开启的总是开；其余管理员定了听管理员的，没定用自带的。 */
    private boolean byDefault(Kind kind, String name) {
        boolean builtin = true;
        if (kind == Kind.PLUGIN) {
            Plugin plugin = plugins.get(name);
            if (plugin != null && plugin.mandatory()) {
                return true;
            }
            builtin = plugin == null || plugin.defaultEnabled();
        }
        Boolean byAdmin = switches.policy(kind, name).map(PluginSwitches.Policy::enabled).orElse(null);
        return byAdmin != null ? byAdmin : builtin;
    }

    /** 这个人选的和默认不一样（页面上的“我改的”）。锁着的时候他选的不算，不显示。 */
    private boolean changed(Principal user, Kind kind, String name) {
        return lock(kind, name) == null
                && switches.chosen(user, kind, name).filter(chosen -> chosen != byDefault(kind, name)).isPresent();
    }

    // ---- 参数的声明 ----

    /** 内置的、有自己开关的工具才有参数；开关组的工具没有（它们没有单独的一行可以放表单）。 */
    private List<Param> toolParams(Tool tool) {
        if (groupOf(tool).isPresent()) {
            return List.of();
        }
        return Declared.of("tool " + tool.name(), tool);
    }

    /** 这一项声明的参数。只有内置工具和钩子插件能有；不认识的名字和别的种类抛 {@link UnknownPlugin}。 */
    private List<Param> declared(Kind kind, String name) {
        if (kind == Kind.TOOL) {
            Tool tool = catalog.find(name).filter(found -> groupOf(found).isEmpty())
                    .orElseThrow(() -> new UnknownPlugin(kind, name));
            return toolParams(tool);
        }
        if (kind == Kind.PLUGIN && plugins.containsKey(name)) {
            return pluginParams.getOrDefault(name, List.of());
        }
        throw new UnknownPlugin(kind, name);
    }

    // ---- 调用 ----

    /** 执行前按当前开关查：关掉的立即拒绝，不执行（需求 8 第 3 条）。 */
    public ToolOutput runTool(ToolContext context, Tool tool, Json arguments) throws Exception {
        if (!toolEnabled(context.principal(), tool)) {
            return ToolOutput.error(TOOL_OFF);
        }
        List<Param> declared = toolParams(tool);
        if (declared.isEmpty()) {
            return tool.run(context, arguments);
        }
        ToolContext mine;
        try {
            mine = withParams(context, Kind.TOOL, tool.name(), declared);
        } catch (RuntimeException failure) {
            // 不带着空参数继续跑：限额、清单这类参数读不到时放行，等于策略没生效
            LOG.error("cannot read the params of tool {}", tool.name(), failure);
            return ToolOutput.error("The settings of this tool could not be read: " + Failures.describe(failure));
        }
        return tool.run(mine, arguments);
    }

    /** 换上这一项此刻的参数和密码；没声明参数的原样返回，不查库。 */
    private ToolContext withParams(ToolContext context, Kind kind, String name, List<Param> declared) {
        if (declared.isEmpty()) {
            return context;
        }
        Principal user = context.principal();
        return context.with(resolver.params(user, kind, name, declared, lock(kind, name) != null),
                resolver.secrets(user, kind, name, declared));
    }

    /**
     * 只跑开着的插件的工具后处理。一个出错：fail-closed 的（自己声明，或所在插件必须开启）让这次调用以错误结束，模型只看到类名；
     * 其余记日志、结果保持上一个。
     */
    public ToolOutput afterTool(ToolContext context, Tool tool, Json arguments, ToolOutput first) {
        ToolOutput output = first;
        for (AfterToolHook hook : afterHooks) {
            if (!pluginEnabled(context.principal(), hook.plugin())) {
                continue;
            }
            try {
                ToolContext mine = withParams(context, Kind.PLUGIN, hook.plugin(), pluginParams.getOrDefault(hook.plugin(), List.of()));
                output = hook.afterToolExecution(mine, tool.name(), arguments, output);
            } catch (Exception failure) {
                LOG.error("afterToolExecution hook of {} failed for tool {}", hook.plugin(), tool.name(), failure);
                Plugin plugin = plugins.get(hook.plugin());
                if (hook.failClosed() || plugin != null && plugin.mandatory()) {
                    return ToolOutput.error("The plugin " + hook.plugin() + " rejected this tool's output: " + Failures.describe(failure));
                }
            }
        }
        return output;
    }

    /** 插件关掉了就不做这次工作，以失败结束。 */
    public WorkResult runWork(ToolContext context, WorkType type, Json input) throws Exception {
        if (!pluginEnabled(context.principal(), type.plugin())) {
            throw new IllegalStateException("plugin " + type.plugin() + " was turned off in the plugin page");
        }
        return type.run(withParams(context, Kind.PLUGIN, type.plugin(), pluginParams.getOrDefault(type.plugin(), List.of())), input);
    }

    // ---- 开关 ----

    /**
     * 只改这个用户自己的。不存在的名字抛 {@link UnknownPlugin}；锁着的开关改不了，抛 {@link LockedPlugin}。
     * 开关组（本机工作区、联网）的工具没有单独的开关，只能用组的种类（{@code LOCAL}、{@code WEB}）开关总开关和子开关，
     * 以及这一组声明可以逐个勾的工具（{@link ToolGroup#choices}）；
     * 管理员关掉的联网项也能存，插件页上禁用。
     */
    public void set(Principal user, Kind kind, String name, boolean enabled) {
        known(kind, name);
        Lock lock = lock(kind, name);
        // 必须开启的插件“打开”是空操作，照旧放过；管理员锁的可能锁在关上，开和关都不行
        if (lock != null && !(enabled && !Lock.ADMIN.equals(lock.source()))) {
            throw new LockedPlugin(name, lock);
        }
        switches.set(user, kind, name, enabled);
    }

    /** 开关恢复默认：忘掉这个人选过什么。锁着的时候也可以（他选的本来就不算）。 */
    public void reset(Principal user, Kind kind, String name) {
        known(kind, name);
        switches.clear(user, kind, name);
    }

    /**
     * 管理员定默认和锁定：{@code enabled} 为 null 是不改默认（用自带的）；{@code reason} 只在锁定时留着。
     * 只管内置工具和钩子插件；代码或配置里定了必须开启的，这里改不了，抛 {@link LockedPlugin}。
     */
    public void setDefault(Kind kind, String name, Boolean enabled, boolean locked, String reason) {
        if (kind == Kind.LOCAL || kind == Kind.WEB) {
            throw new UnknownPlugin(kind, name);
        }
        known(kind, name);
        Lock lock = lock(kind, name);
        if (lock != null && !Lock.ADMIN.equals(lock.source())) {
            throw new LockedPlugin(name, lock);
        }
        switches.setPolicy(kind, name, enabled, locked, reason);
    }

    private void known(Kind kind, String name) {
        boolean found = switch (kind) {
            case TOOL -> catalog.find(name).filter(tool -> groupOf(tool).isEmpty()).isPresent();
            case LOCAL, WEB -> groups.stream().anyMatch(group -> group.kind() == kind && (group.switches().contains(name)
                    || group.choices().values().stream().anyMatch(tools -> tools.contains(name))));
            case PLUGIN -> plugins.containsKey(name);
        };
        if (!found) {
            throw new UnknownPlugin(kind, name);
        }
    }

    private void refuseIfLocked(Kind kind, String name) {
        Lock lock = lock(kind, name);
        if (lock != null) {
            throw new LockedPlugin(name, lock);
        }
    }

    // ---- 参数 ----

    /**
     * 用户保存自己的参数。只收归用户的键：归管理员的抛 {@link ForbiddenParam}，没声明的抛 {@link InvalidParam}（{@code unknown}）。
     * 密码键给了就替换，没给不动。开关锁着时普通参数改不了（抛 {@link LockedPlugin}），密码照样能存。
     * 整份检查完才写：有一个不合格，一个都不存。
     */
    public void saveParams(Principal user, Kind kind, String name, Map<String, Json> values) {
        Map<String, Param> declared = byKey(declared(kind, name));
        Map<String, Json> plain = new LinkedHashMap<>();
        Map<String, String> secrets = new LinkedHashMap<>();
        for (Map.Entry<String, Json> entry : values.entrySet()) {
            Param param = param(declared, entry.getKey());
            if (param.owner() == Param.Owner.ADMIN) {
                throw new ForbiddenParam(param.key());
            }
            Json checked = ParamRules.check(param, entry.getValue());
            if (param.secret()) {
                secrets.put(param.key(), ((Json.Str) checked).value());
            } else {
                plain.put(param.key(), checked);
            }
        }
        if (!plain.isEmpty()) {
            refuseIfLocked(kind, name);
            store.save(Scope.OWN, user, kind, name, plain, Set.of());
        }
        secrets.forEach((key, secret) -> store.putSecret(user, kind, name, key, secret));
    }

    /** 参数恢复默认：删掉这个人自己填的全部普通参数。密码不动。 */
    public void resetParams(Principal user, Kind kind, String name) {
        declared(kind, name);
        store.clear(Scope.OWN, user, kind, name);
    }

    /** 清除一个密码。只对声明成密码的键。 */
    public void clearSecret(Principal user, Kind kind, String name, String key) {
        Param param = param(byKey(declared(kind, name)), key);
        if (!param.secret()) {
            throw new InvalidParam(key, "unknown");
        }
        store.clearSecret(user, kind, name, key);
    }

    /** 管理员定参数的默认值，归谁的键都可以；值是 JSON null 表示清掉、回到自带的。密码没有默认。 */
    public void saveDefaults(Kind kind, String name, Map<String, Json> values) {
        Map<String, Param> declared = byKey(declared(kind, name));
        save(Scope.DEFAULT, null, kind, name, values, key -> {
            Param param = param(declared, key);
            if (param.secret()) {
                throw new InvalidParam(key, "unknown");
            }
            return param;
        });
    }

    /** 管理员给一个人指定参数，只收归管理员的键；值是 JSON null 表示清掉、回到默认。 */
    public void assign(Principal user, Kind kind, String name, Map<String, Json> values) {
        Map<String, Param> declared = byKey(declared(kind, name));
        save(Scope.ASSIGNED, user, kind, name, values, key -> {
            Param param = param(declared, key);
            if (param.owner() != Param.Owner.ADMIN) {
                throw new ForbiddenParam(key);
            }
            return param;
        });
    }

    private void save(Scope scope, Principal user, Kind kind, String name, Map<String, Json> values, Function<String, Param> allowed) {
        Map<String, Json> put = new LinkedHashMap<>();
        Set<String> remove = new LinkedHashSet<>();
        for (Map.Entry<String, Json> entry : values.entrySet()) {
            Param param = allowed.apply(entry.getKey());
            if (entry.getValue() instanceof Json.Null) {
                remove.add(param.key());
            } else {
                put.put(param.key(), ParamRules.check(param, entry.getValue()));
            }
        }
        if (!put.isEmpty() || !remove.isEmpty()) {
            store.save(scope, user, kind, name, put, remove);
        }
    }

    private static Map<String, Param> byKey(List<Param> declared) {
        Map<String, Param> byKey = new LinkedHashMap<>();
        declared.forEach(param -> byKey.put(param.key(), param));
        return byKey;
    }

    private static Param param(Map<String, Param> declared, String key) {
        Param param = declared.get(key);
        if (param == null) {
            throw new InvalidParam(key, "unknown");
        }
        return param;
    }

    public static final class UnknownPlugin extends RuntimeException {
        private final String name;

        UnknownPlugin(Kind kind, String name) {
            super("no such " + kind.key() + ": " + name);
            this.name = name;
        }

        /** 插件、工具或服务器的名字，控制台的错误文字要用。 */
        public String name() {
            return name;
        }
    }

    /** 开关锁着，改不了。{@link #lock()} 说是谁锁的、为什么。 */
    public static final class LockedPlugin extends IllegalArgumentException {
        private final String name;
        private final transient Lock lock;

        LockedPlugin(String name, Lock lock) {
            super(name + " is locked (" + lock.source() + ") and cannot be changed");
            this.name = name;
            this.lock = lock;
        }

        public String name() {
            return name;
        }

        public Lock lock() {
            return lock;
        }
    }

    /** 这个参数只有管理员能改。 */
    public static final class ForbiddenParam extends RuntimeException {
        private final String key;

        ForbiddenParam(String key) {
            super("param " + key + " can only be changed by an administrator");
            this.key = key;
        }

        public String key() {
            return key;
        }
    }

    /** 成员自己声明必须开启的插件。 */
    private static Map<String, String> modelNotes(List<KernelHook> hooks, List<AfterToolHook> afterHooks) {
        Map<String, String> notes = new LinkedHashMap<>();
        for (KernelHook hook : hooks) {
            if (hook instanceof PluginInfo info && info.modelNote() != null && !info.modelNote().isBlank()) {
                notes.putIfAbsent(hook.plugin(), info.modelNote().strip());
            }
        }
        for (AfterToolHook hook : afterHooks) {
            if (hook instanceof PluginInfo info && info.modelNote() != null && !info.modelNote().isBlank()) {
                notes.putIfAbsent(hook.plugin(), info.modelNote().strip());
            }
        }
        return Map.copyOf(notes);
    }

    private static Set<String> lockedInCode(List<KernelHook> hooks, List<AfterToolHook> afterHooks) {
        Set<String> names = new LinkedHashSet<>();
        hooks.stream().filter(hook -> hook instanceof PluginInfo info && info.mandatory()).forEach(hook -> names.add(hook.plugin()));
        afterHooks.stream().filter(hook -> hook instanceof PluginInfo info && info.mandatory()).forEach(hook -> names.add(hook.plugin()));
        return Set.copyOf(names);
    }

    private static Map<String, List<DailyLimit>> dailyLimits(List<KernelHook> hooks, List<AfterToolHook> afterHooks, List<WorkType> workTypes) {
        Map<String, List<DailyLimit>> members = new LinkedHashMap<>();
        hooks.forEach(hook -> dailyLimit(hook, hook.plugin(), members));
        afterHooks.forEach(hook -> dailyLimit(hook, hook.plugin(), members));
        workTypes.forEach(type -> dailyLimit(type, type.plugin(), members));
        return members;
    }

    private static void dailyLimit(Object member, String plugin, Map<String, List<DailyLimit>> members) {
        if (member instanceof DailyLimit limit) {
            members.computeIfAbsent(plugin, ignored -> new ArrayList<>()).add(limit);
        }
    }

    /** 每个钩子插件的参数：钩子、工具后处理、插件工作的并在一起，按这个顺序。 */
    private static Map<String, List<Param>> declared(List<KernelHook> hooks, List<AfterToolHook> afterHooks, List<WorkType> workTypes) {
        Map<String, Map<String, Param>> merged = new LinkedHashMap<>();
        hooks.forEach(hook -> Declared.merge("plugin " + hook.plugin(), hook, merged.computeIfAbsent(hook.plugin(), ignored -> new LinkedHashMap<>())));
        afterHooks.forEach(hook -> Declared.merge("plugin " + hook.plugin(), hook, merged.computeIfAbsent(hook.plugin(), ignored -> new LinkedHashMap<>())));
        workTypes.forEach(type -> Declared.merge("plugin " + type.plugin(), type, merged.computeIfAbsent(type.plugin(), ignored -> new LinkedHashMap<>())));
        Map<String, List<Param>> declared = new LinkedHashMap<>();
        merged.forEach((plugin, params) -> {
            if (!params.isEmpty()) {
                declared.put(plugin, List.copyOf(params.values()));
            }
        });
        return declared;
    }

    private static Map<String, Plugin> group(List<KernelHook> hooks, List<AfterToolHook> afterHooks, List<WorkType> workTypes,
                                             Set<String> required) {
        Map<String, List<String>> points = new LinkedHashMap<>();
        Map<String, LocalText> descriptions = new LinkedHashMap<>();
        Map<String, Boolean> mandatory = new LinkedHashMap<>();
        Map<String, Boolean> byDefault = new LinkedHashMap<>();
        for (KernelHook hook : hooks) {
            List<String> list = points.computeIfAbsent(hook.plugin(), ignored -> new ArrayList<>());
            for (String point : HOOK_POINTS) {
                if (overrides(hook, point)) {
                    list.add(point);
                }
            }
            info(hook, hook.plugin(), descriptions, mandatory, byDefault);
        }
        for (AfterToolHook hook : afterHooks) {
            points.computeIfAbsent(hook.plugin(), ignored -> new ArrayList<>()).add("afterToolExecution");
            info(hook, hook.plugin(), descriptions, mandatory, byDefault);
        }
        for (WorkType type : workTypes) {
            points.computeIfAbsent(type.plugin(), ignored -> new ArrayList<>()).add("work:" + type.name());
            descriptions.merge(type.plugin(), type.description(), PluginGate::first);
        }
        List<String> unknown = required.stream().filter(name -> !points.containsKey(name)).sorted().toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("tantalum.hooks.required names plugins that are not installed: " + String.join(", ", unknown)
                    + "; installed: " + String.join(", ", points.keySet().stream().sorted().toList()));
        }
        Map<String, Plugin> grouped = new LinkedHashMap<>();
        points.keySet().stream().sorted().forEach(name -> grouped.put(name, new Plugin(name, descriptions.getOrDefault(name, LocalText.empty()),
                points.get(name).stream().distinct().toList(), mandatory.getOrDefault(name, false) || required.contains(name),
                byDefault.getOrDefault(name, true))));
        return grouped;
    }

    /** 插件页要的说明和开关策略只从实现了 {@link PluginInfo} 的成员取；没实现的什么都不说。 */
    private static void info(Object member, String plugin, Map<String, LocalText> descriptions, Map<String, Boolean> mandatory,
                             Map<String, Boolean> byDefault) {
        if (member instanceof PluginInfo info) {
            descriptions.merge(plugin, info.description(), PluginGate::first);
            mandatory.merge(plugin, info.mandatory(), Boolean::logicalOr);
            byDefault.merge(plugin, info.defaultEnabled(), Boolean::logicalAnd);
        }
    }

    /** {@code BEFORE_MODEL_CALL} → {@code beforeModelCall}：{@link KernelHook} 的回调就按这个规律命名。 */
    static String callback(HookPoint point) {
        StringBuilder name = new StringBuilder();
        boolean upper = false;
        for (char c : point.name().toCharArray()) {
            if (c == '_') {
                upper = true;
            } else {
                name.append(upper ? Character.toUpperCase(c) : Character.toLowerCase(c));
                upper = false;
            }
        }
        return name.toString();
    }

    private static LocalText first(LocalText earlier, LocalText later) {
        return earlier.isEmpty() ? later : earlier;
    }

    private static boolean overrides(KernelHook hook, String point) {
        try {
            return hook.getClass().getMethod(point, KernelState.class, HookContext.class).getDeclaringClass() != KernelHook.class;
        } catch (NoSuchMethodException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
