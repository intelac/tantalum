package tantalum.shell.handler;

import java.time.Clock;
import java.time.Duration;
import static tantalum.shell.handler.ToolHandler.*;
import tantalum.shell.access.PluginGate;
import tantalum.shell.handler.ToolHandler.*;
import tantalum.shell.tool.ToolCatalog;
import tantalum.shell.tool.Workspaces;

/** 测试里用的简短建法：主代码只留参数最全的构造函数，给它加字段时只改这里。 */
public final class ToolHandlerFixture {

    private ToolHandlerFixture() {}

    public static ToolHandler of(ToolCatalog catalog, Workspaces workspaces, PluginGate gate, Duration timeout, Clock clock) {
        return new ToolHandler(catalog, workspaces, gate, timeout, clock, session -> null);
    }
}
