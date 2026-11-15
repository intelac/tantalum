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
import tantalum.shell.fetch.Fetcher;
import tantalum.shell.fetch.WebFetchTool;
import tantalum.shell.local.LocalLinks;
import tantalum.shell.local.LocalTool;
import tantalum.shell.local.LocalWorkspace;
import tantalum.shell.net.AddressRules;
import tantalum.shell.net.Egress;
import tantalum.shell.net.UpstreamProxy;
import tantalum.shell.panel.Panel;
import tantalum.shell.plugin.AfterToolHook;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.WorkType;
import tantalum.shell.route.Routes;
import tantalum.shell.settings.CompactionPolicy;
import tantalum.shell.skill.SkillCatalog;
import tantalum.shell.skill.SkillMentions;
import tantalum.shell.skill.SkillSettings;
import tantalum.shell.tool.BuiltinTools;
import tantalum.shell.tool.ToolCatalog;

/**
 * 装配第二步：工具、插件和联网——工具目录、连到电脑的连接、出口和浏览器、插件开关的入口、模型路线。
 * 管理员关掉的那一项不注册工具；这一组照样进开关，插件页照样显示。
 */
record Toolbox(ToolCatalog catalog, ToolImages toolImages, LocalLinks localLinks, LocalWorkspace localWorkspace, SkillSettings skillSettings,
               SkillCatalog skillCatalog, SkillMentions skillMentions, AddressRules addressRules,
               Egress egress, WebAccess webAccess, Routes routes,
               CompactionPolicy compaction, PluginGate gate) {

    /** 读模型列表：启动时几条路线并行，总共最多等 5 秒，之后每 10 分钟（同 DSH {@code listing-config.ts:7,9}）。 */
    private static final Duration LIST_STARTUP_WAIT = Duration.ofSeconds(5);

    static Toolbox open(TantalumProperties properties, Stores stores, Routes routes, List<Tool> tools, List<KernelHook> hooks,
                        List<WorkType> workTypes, List<AfterToolHook> afterHooks, List<Panel> panels, List<Object> members, Clock clock,
                        List<AutoCloseable> opened) throws IOException {
        ToolCatalog catalog = new ToolCatalog();
        LocalLinks localLinks = new LocalLinks(stores.localTokens(), clock, LocalLinks.Timing.DEFAULT);
        opened.add(localLinks);
        // 本机工作区读到的图片、浏览器截图都照附件规则存进本人附件
        ToolImages toolImages = new ToolImages(stores.storage().attachments);
        // 服务器上只读附件；读写文件、Git 都转发到用户的电脑
        BuiltinTools.of(stores.storage().attachments).forEach(catalog::register);
        SkillSettings skillSettings = new SkillSettings(stores.params());
        LocalTool.all(localLinks, toolImages, properties.toolTimeout(), skillSettings).forEach(catalog::register);
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
        LocalWorkspace localWorkspace = new LocalWorkspace(stores.switches(), localLinks, skillSettings);
        SkillCatalog skillCatalog = new SkillCatalog(localWorkspace);
        WebAccess webAccess = new WebAccess(stores.switches(), web.fetch().enabled(), web.blockPrivateNetwork());
        // 每个模型的窗口在它的路线的列表里，每次提问按这次用的模型算压缩设置
        Routes started = routes.start(LIST_STARTUP_WAIT);
        CompactionPolicy compaction = new CompactionPolicy(stores.userSettings(), properties.compactionLimits());
        PluginGate gate = new PluginGate(catalog, stores.switches(), List.of(localWorkspace, webAccess),
                hooks, afterHooks, workTypes, panels, members, Set.copyOf(properties.hooks().required()), stores.params());
        return new Toolbox(catalog, toolImages, localLinks, localWorkspace, skillSettings, skillCatalog,
                new SkillMentions(localWorkspace, skillCatalog), addressRules, egress, webAccess,
                started, compaction, gate);
    }

    /**
     * 固定说明：“网页内容不可信”那段，不随用户开关、管理员的配置变化。总是有：浏览器工具的定义总是注册着（在用户电脑上执行），
     * 不论服务器开没开抓网页。
     */
    String fixedNote() {
        return WebAccess.NOTE;
    }
}
