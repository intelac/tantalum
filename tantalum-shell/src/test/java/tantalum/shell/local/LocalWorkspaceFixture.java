package tantalum.shell.local;

import static tantalum.shell.local.LocalWorkspace.*;
import tantalum.shell.access.PluginSwitches;
import tantalum.shell.local.LocalWorkspace.*;

/** 测试里用的简短建法：主代码只留参数最全的构造函数，给它加字段时只改这里。 */
public final class LocalWorkspaceFixture {

    private LocalWorkspaceFixture() {}

    public static LocalWorkspace of(PluginSwitches switches, LocalLinks links) {
        return new LocalWorkspace(switches, links, LocalDirs.NONE);
    }
}
