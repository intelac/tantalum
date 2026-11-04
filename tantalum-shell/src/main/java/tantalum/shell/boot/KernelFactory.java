package tantalum.shell.boot;

import java.time.Clock;
import java.util.List;
import tantalum.kernel.Kernel;
import tantalum.kernel.Limits;
import tantalum.kernel.hook.HookChain;
import tantalum.kernel.hook.KernelHook;
import tantalum.kernel.step.BuiltinSteps;
import tantalum.kernel.table.TransitionTable;
import tantalum.kernel.tool.ToolSafety;
import tantalum.shell.memory.MemoryStores;
import tantalum.shell.memory.MemoryTools;
import tantalum.shell.plugin.Tool;
import tantalum.shell.tool.BuiltinTools;
import tantalum.shell.tool.Observations;
import tantalum.shell.tool.ToolCatalog;
import tantalum.shell.tool.Workspaces;

/** 建内核：服务器和检查工具用同一份，两边的转移表、钩子、工具调度规则和上限相同（record-results R12）。 */
final class KernelFactory {

    private KernelFactory() {}

    static Kernel kernel(TantalumProperties properties, ToolSafety safety, List<KernelHook> hooks) {
        TransitionTable table = new TransitionTable();
        BuiltinSteps.register(table);
        return new Kernel(table, new HookChain(hooks), safety,
                new Limits(properties.loop().maxParallelTools(), properties.hooks().maxWorkRounds()));
    }

    static Workspaces workspaces(TantalumProperties properties) {
        return properties.workspaceMode().equals("shared")
                ? Workspaces.shared(properties.workspace()) : Workspaces.perUser(properties.workspace());
    }

    /**
     * 只回答调度问题的工具目录：和服务器注册同样的内置工具、记忆工具和插件工具，但不打开附件库。
     * MCP 工具恒为独占（{@code McpTool}），目录里没有它们时答案相同，所以不用连 MCP 服务器。
     */
    static ToolSafety safety(TantalumProperties properties, List<Tool> plugins) {
        ToolCatalog catalog = new ToolCatalog();
        Workspaces workspaces = workspaces(properties);
        BuiltinTools.of(workspaces, null, new Observations()).forEach(catalog::register);
        MemoryTools.all(new MemoryStores(workspaces, properties.dataDir(), properties.memory(), Clock.systemUTC()))
                .forEach(catalog::register);
        plugins.forEach(catalog::register);
        return catalog.safety();
    }
}
