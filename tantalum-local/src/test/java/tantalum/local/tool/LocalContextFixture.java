package tantalum.local.tool;

import static tantalum.local.tool.LocalContext.*;
import tantalum.local.fs.AllowedDirs;
import tantalum.local.shell.Shell;
import tantalum.local.tool.LocalContext.*;

/** 测试里用的简短建法：主代码只留参数最全的构造函数，给它加字段时只改这里。 */
public final class LocalContextFixture {

    private LocalContextFixture() {}

    public static LocalContext of(String session, AllowedDirs dirs, Observations observations) {
        return new LocalContext(session, dirs, observations, NO_SHELL, null, null);
    }

    public static LocalContext of(String session, AllowedDirs dirs, Observations observations, Shell shell) {
        return new LocalContext(session, dirs, observations, shell, null, null);
    }

    public static LocalContext of(String session, AllowedDirs dirs, Observations observations, Shell shell, String skills) {
        return new LocalContext(session, dirs, observations, shell, skills, null);
    }
}
