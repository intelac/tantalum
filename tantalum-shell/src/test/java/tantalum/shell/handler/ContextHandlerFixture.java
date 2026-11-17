package tantalum.shell.handler;

import java.util.List;
import static tantalum.shell.handler.ContextHandler.*;
import tantalum.shell.access.PluginGate;
import tantalum.shell.handler.ContextHandler.*;
import tantalum.shell.knowledge.KnowledgeBase;
import tantalum.shell.local.LocalWorkspace;
import tantalum.shell.plugin.ContextContributor;
import tantalum.shell.route.Routes;
import tantalum.shell.settings.CompactionPolicy;
import tantalum.shell.settings.UserSettings;
import tantalum.shell.tool.ToolCatalog;

/** 测试里用的简短建法：主代码只留参数最全的构造函数，给它加字段时只改这里。 */
public final class ContextHandlerFixture {

    private ContextHandlerFixture() {}

    public static ContextHandler of(LocalWorkspace local, String basePrompt, ToolCatalog catalog, PluginGate gate, List<ContextContributor> contributors, KnowledgeBase knowledge, String fixedNote, CompactionPolicy compaction, Routes routes, UserSettings users) {
        return new ContextHandler(local, basePrompt, catalog, gate, contributors, knowledge, fixedNote, compaction, routes, users, session -> null);
    }
}
