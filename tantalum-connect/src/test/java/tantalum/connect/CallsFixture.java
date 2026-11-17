package tantalum.connect;

import java.util.List;
import static tantalum.connect.Calls.*;
import tantalum.connect.Calls.*;
import tantalum.local.fs.AllowedDirs;
import tantalum.local.shell.Shell;
import tantalum.local.shell.Unavailable;
import tantalum.local.tool.LocalTool;

/** 测试里用的简短建法：主代码只留参数最全的构造函数，给它加字段时只改这里。 */
public final class CallsFixture {

    private CallsFixture() {}

    public static Calls of(AllowedDirs dirs, boolean write, boolean shell, Journal journal, List<LocalTool> tools) {
        return new Calls(dirs, write, shell, Shell.unavailable(Unavailable.of(Unavailable.Code.SHELL_NOT_ALLOWED)), journal, tools);
    }
}
