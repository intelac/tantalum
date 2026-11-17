package tantalum.shell.plugin;

import java.time.Instant;
import static tantalum.shell.plugin.RunFinishedListener.*;
import tantalum.shell.plugin.RunFinishedListener.*;
import tantalum.value.id.Principal;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;

/** 测试里用的简短建法：主代码只留参数最全的构造函数，给它加字段时只改这里。 */
public final class RunFinishedListenerFixture {

    private RunFinishedListenerFixture() {}

    public static RunFinishedListener.FinishedRun finishedRun(Principal user, SessionId session, RunId run, Integer turn, String endReason, boolean manual, Instant start, Instant end, String title, String excerpt) {
        return new RunFinishedListener.FinishedRun(user, session, run, turn, endReason, manual, start, end, title, excerpt, java.util.List.of(), false);
    }
}
