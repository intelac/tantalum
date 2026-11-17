package tantalum.shell.boot;

import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Function;
import static tantalum.shell.boot.PluginRunListeners.*;
import tantalum.shell.boot.PluginRunListeners.*;
import tantalum.shell.plugin.RunFinishedListener;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;

/** 测试里用的简短建法：主代码只留参数最全的构造函数，给它加字段时只改这里。 */
public final class PluginRunListenersFixture {

    private PluginRunListenersFixture() {}

    public static PluginRunListeners of(List<RunFinishedListener> listeners, BiPredicate<String, Principal> enabled, Records records, Function<SessionId, String> titles) {
        return new PluginRunListeners(listeners, enabled, records, titles, tool -> null, (user, session) -> false, java.util.Set::of);
    }
}
