package tantalum.shell.boot;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import tantalum.kernel.Kernel;
import tantalum.kernel.Limits;
import tantalum.kernel.hook.FailClosed;
import tantalum.kernel.hook.HookChain;
import tantalum.kernel.hook.KernelHook;
import tantalum.kernel.step.BuiltinSteps;
import tantalum.kernel.table.TransitionTable;
import tantalum.kernel.tool.ToolSafety;
import tantalum.shell.fetch.WebFetchTool;
import tantalum.shell.local.LocalTool;
import tantalum.shell.memory.MemoryTools;
import tantalum.shell.plugin.PluginInfo;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.ToolDispatch;
import tantalum.shell.tool.BuiltinTools;
import tantalum.shell.tool.Workspaces;

/** 建内核：服务器和检查工具用同一份，两边的转移表、钩子、工具调度规则和上限相同。 */
final class KernelFactory {

    private KernelFactory() {}

    static Kernel kernel(TantalumProperties properties, ToolSafety safety, List<KernelHook> hooks) {
        TransitionTable table = new TransitionTable();
        BuiltinSteps.register(table);
        return new Kernel(table, new HookChain(failClosed(hooks, Set.copyOf(properties.hooks().required()))), safety,
                new Limits(properties.loop().maxParallelTools(), properties.hooks().maxWorkRounds()));
    }

    /**
     * 必须开启的插件（自己声明 {@link PluginInfo#mandatory()} 或管理员配置 {@code tantalum.hooks.required}）的钩子出错按拦截处理：
     * 内核只认钩子自己的 {@code failClosed()}，所以在这里包一层再交给内核。
     */
    static List<KernelHook> failClosed(List<KernelHook> hooks, Set<String> required) {
        Set<String> mandatory = new HashSet<>(required);
        for (KernelHook hook : hooks) {
            if (hook instanceof PluginInfo info && info.mandatory()) {
                mandatory.add(hook.plugin());
            }
        }
        return hooks.stream().map(hook -> mandatory.contains(hook.plugin()) && !hook.failClosed() ? new FailClosed(hook) : hook).toList();
    }

    static Workspaces workspaces(TantalumProperties properties) {
        return properties.workspaceMode().equals("shared")
                ? Workspaces.shared(properties.workspace()) : Workspaces.perUser(properties.workspace());
    }

    /**
     * 只回答调度问题的规则：和服务器注册同样的内置工具、转发工具、记忆工具、{@code web_fetch} 和插件工具的调度描述，
     * 不打开附件库、不连电脑、不起出口、不开记忆库。
     * MCP 工具恒为独占（{@code McpTool}），没有它们时答案相同，所以不用连 MCP 服务器；浏览器工具同样独占，不用登记。
     */
    static ToolSafety safety(TantalumProperties properties, List<Tool> plugins) {
        List<ToolDispatch> dispatches = new ArrayList<>();
        dispatches.addAll(BuiltinTools.dispatch());
        dispatches.addAll(LocalTool.dispatches());
        if (properties.webSettings().fetch().enabled()) {
            dispatches.add(WebFetchTool.DISPATCH);
        }
        dispatches.addAll(MemoryTools.dispatch());
        plugins.forEach(plugin -> dispatches.add(plugin.dispatch()));
        return ToolDispatch.safety(dispatches);
    }
}
