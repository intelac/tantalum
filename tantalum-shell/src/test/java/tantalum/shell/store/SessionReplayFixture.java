package tantalum.shell.store;

import static tantalum.shell.store.SessionReplay.*;
import tantalum.kernel.Kernel;
import tantalum.shell.journal.SessionFileReader;
import tantalum.shell.journal.SessionPaths;
import tantalum.shell.store.SessionReplay.*;

/** 测试里用的简短建法：主代码只留参数最全的构造函数，给它加字段时只改这里。 */
public final class SessionReplayFixture {

    private SessionReplayFixture() {}

    public static SessionReplay of(SessionPaths paths, SessionFileReader reader, UsageLedger usage, Kernel kernel) {
        return new SessionReplay(paths, reader, (Usage) usage::cursor, kernel);
    }
}
