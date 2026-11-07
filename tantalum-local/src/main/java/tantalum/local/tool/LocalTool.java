package tantalum.local.tool;

import java.time.Duration;
import tantalum.value.json.Json;

/**
 * 在用户电脑上执行的工具。定义部分（名字、说明、参数）固定，服务器用它注册转发工具；
 * 连接程序用 {@link #run} 执行。
 */
public interface LocalTool {

    /** 工具的类别，对应插件页的四个子开关和连接程序的 {@code --allow-*}。 */
    enum Kind {
        /** 读文件，有 {@code --allow} 就有。 */
        READ,
        /** Git 查询：电脑上装了 git 就有，每次调用按路径找仓库。 */
        GIT,
        /** 改文件，要 {@code --allow-write}。 */
        WRITE,
        /** 跑命令，要 {@code --allow-shell} 而且系统沙箱可用。 */
        SHELL
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
}
