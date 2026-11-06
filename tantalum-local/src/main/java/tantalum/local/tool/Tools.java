package tantalum.local.tool;

import java.util.List;

/**
 * 本机工作区的全部工具，顺序和名字固定（L9：工具定义不随电脑变，缓存前缀才不失效）。服务器用它注册转发工具，只用定义部分；
 * 连接程序用它执行。文件工具的名字和参数沿用服务器上原来的（L5），旧会话里的调用对得上。工具都没有状态，可以共用。
 */
public final class Tools {

    private static final List<LocalTool> ALL = List.of(
            new ReadFile(), new Grep(), new ListDir(), new FindFiles(), new WriteFile(), new EditFile(),
            new GitLog(), new GitShow(), new GitDiff(), new GitBlame(), new GitBranches(), new Bash());

    private Tools() {}

    public static List<LocalTool> all() {
        return ALL;
    }
}
