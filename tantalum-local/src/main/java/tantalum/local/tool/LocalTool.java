package tantalum.local.tool;

import java.time.Duration;
import java.util.List;
import tantalum.value.json.Json;

/**
 * 在用户电脑上执行的工具。定义部分（名字、说明、参数）固定，服务器用它注册转发工具；
 * 连接程序用 {@link #run} 执行。
 */
public interface LocalTool {

    /** 工具的类别，对应插件页“本机工作区”下面的子开关和连接程序的 {@code --allow-*}。 */
    enum Kind {
        /** 读文件，有 {@code --allow} 就有。 */
        READ,
        /** Git 查询：电脑上装了 git 就有，每次调用按路径找仓库。 */
        GIT,
        /** 改文件，要 {@code --allow-write}。 */
        WRITE,
        /** 跑命令，要 {@code --allow-shell} 而且系统沙箱可用。 */
        SHELL,
        /** 加载技能：只读用户填的技能目录，有 {@code --allow} 就有（协议 4 起）。 */
        SKILL,
        /** 这台电脑上的浏览器，要 {@code --allow-browser} 而且找得到 Chrome 或 Edge（协议 5 起）。 */
        BROWSER
    }

    String name();

    String description();

    /** 参数的 JSON Schema。 */
    Json schema();

    Kind kind();

    /** 必须是纯函数。默认独占。 */
    default boolean isConcurrencySafe(Json arguments) {
        return false;
    }

    /** 这次调用最长能跑多久；默认用服务器的标准超时。 */
    default Duration timeout(Json arguments, Duration standard) {
        return standard;
    }

    /** 在虚拟线程上执行，要响应中断。抛异常等同于错误结果。 */
    LocalResult run(LocalContext context, Json arguments) throws Exception;

    /**
     * 连接程序（和测试电脑）都经这里执行：照常 {@link #run}，参数里有定义之外的名字时在结果末尾说明忽略了哪些。
     * 模型常照别家工具的参数名写（qwen 给 grep 传过 glob、head_limit、pattern_type——Claude Code 的写法），不说的话它以为限制生效了。
     */
    default LocalResult invoke(LocalContext context, Json arguments) throws Exception {
        LocalResult result = run(context, arguments);
        List<String> ignored = ToolArguments.unknown(schema(), arguments);
        return ignored.isEmpty() ? result : result.withNote("(Ignored arguments this tool does not have: " + String.join(", ", ignored) + ".)");
    }
}
