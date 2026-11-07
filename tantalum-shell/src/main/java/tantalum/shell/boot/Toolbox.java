package tantalum.shell.boot;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import tantalum.kernel.hook.KernelHook;
import tantalum.shell.access.PluginGate;
import tantalum.shell.access.WebAccess;
import tantalum.shell.attachment.ToolImages;
import tantalum.shell.browser.BrowserDriver;
import tantalum.shell.browser.BrowserTools;
import tantalum.shell.browser.Browsers;
import tantalum.shell.fetch.Fetcher;
import tantalum.shell.fetch.WebFetchTool;
import tantalum.shell.local.LocalLinks;
import tantalum.shell.local.LocalTool;
import tantalum.shell.local.LocalWorkspace;
import tantalum.shell.mcp.McpRegistry;
import tantalum.shell.mcp.McpSettings;
import tantalum.shell.memory.MemoryTools;
import tantalum.shell.net.AddressRules;
import tantalum.shell.net.Egress;
import tantalum.shell.net.UpstreamProxy;
import tantalum.shell.plugin.AfterToolHook;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.WorkType;
import tantalum.shell.route.Routes;
import tantalum.shell.settings.CompactionPolicy;
import tantalum.shell.tool.BuiltinTools;
import tantalum.shell.tool.ToolCatalog;

/**
 * 装配第二步：工具、插件和联网——工具目录、连到电脑的连接、出口和浏览器、MCP、插件开关的入口、模型路线。
 * 管理员关掉的那一项不注册工具；这一组照样进开关，插件页照样显示。
 */
record Toolbox(ToolCatalog catalog, ToolImages toolImages, LocalLinks localLinks, LocalWorkspace localWorkspace, AddressRules addressRules,
               Egress egress, Browsers browsers, WebAccess webAccess, McpRegistry mcp, McpSettings mcpSettings, Routes routes,
               CompactionPolicy compaction, PluginGate gate, boolean web) {

    /** 读模型列表：启动时几条路线并行，总共最多等 5 秒，之后每 10 分钟（同 DSH {@code listing-config.ts:7,9}）。 */
    private static final Duration LIST_STARTUP_WAIT = Duration.ofSeconds(5);

    static Toolbox open(TantalumProperties properties, Stores stores, Routes routes, List<Tool> tools, List<KernelHook> hooks,
                        List<WorkType> workTypes, List<AfterToolHook> afterHooks, Clock clock, List<AutoCloseable> opened) throws IOException {
        ToolCatalog catalog = new ToolCatalog();
        LocalLinks localLinks = new LocalLinks(stores.localTokens(), clock, LocalLinks.Timing.DEFAULT);
        opened.add(localLinks);
        // 本机工作区读到的图片、MCP 返回的图片、浏览器截图都照附件规则存进本人附件
        ToolImages toolImages = new ToolImages(stores.storage().attachments);
        // 服务器上只读附件；读写文件、Git 都转发到用户的电脑
        BuiltinTools.of(stores.storage().attachments).forEach(catalog::register);
        LocalTool.all(localLinks, toolImages, properties.toolTimeout()).forEach(catalog::register);
        MemoryTools.all(stores.memories()).forEach(catalog::register);
        tools.forEach(catalog::register);

        TantalumProperties.Web web = properties.webSettings();
        AddressRules addressRules = new AddressRules(web.blockPrivateNetwork());
        Egress egress = web.any() ? Egress.start(addressRules, UpstreamProxy.fromEnvironment(System::getenv)) : null;
        if (egress != null) {
            opened.add(egress);
        }
        if (web.fetch().enabled()) {
            catalog.register(new WebFetchTool(new Fetcher(egress, addressRules)));
        }
        // 浏览器懒启动：这里只登记，用户第一次调浏览器工具时才起 Chromium
        TantalumProperties.Browser browser = web.browser();
        Browsers browsers = browser.enabled() ? new Browsers(new Browsers.Settings(browser.maxOpen(), browser.idleClose(), browser.sandbox()),
                egress, toolImages, clock, new BrowserDriver(properties.dataDir())) : null;
        if (browsers != null) {
            opened.add(browsers);
            BrowserTools.all(browsers).forEach(catalog::register);
        }
        McpSettings mcpSettings = McpSettings.from(properties.mcp(), properties.toolTimeout(), System::getenv);
        McpRegistry mcp = new McpRegistry(mcpSettings, toolImages);
        opened.add(mcp);
        catalog.setSource(mcp);
        LocalWorkspace localWorkspace = new LocalWorkspace(stores.switches(), localLinks);
        WebAccess webAccess = new WebAccess(stores.switches(), web.fetch().enabled(), browser.enabled(), web.blockPrivateNetwork());
        // 每个模型的窗口在它的路线的列表里，每次提问按这次用的模型算压缩设置
        Routes started = routes.start(LIST_STARTUP_WAIT);
        CompactionPolicy compaction = new CompactionPolicy(stores.userSettings(), properties.compactionLimits());
        PluginGate gate = new PluginGate(catalog, stores.switches(), List.of(localWorkspace, webAccess),
                mcpSettings.servers().stream().map(McpSettings.Server::name).toList(),
                hooks, afterHooks, workTypes, Set.copyOf(properties.hooks().required()));
        return new Toolbox(catalog, toolImages, localLinks, localWorkspace, addressRules, egress, browsers, webAccess, mcp, mcpSettings,
                started, compaction, gate, web.any());
    }

    /** 固定说明：MCP 的那段加联网的那段，都不随用户开关变化。 */
    String fixedNote() {
        String mcpNote = mcp.fixedNote();
        if (!web) {
            return mcpNote;
        }
        return mcpNote.isEmpty() ? WebAccess.NOTE : mcpNote + "\n\n" + WebAccess.NOTE;
    }
}
