package tantalum.local.shell;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 交给命令和浏览器（Playwright MCP）子进程的环境变量：连接程序自己的环境变量，去掉名字像密钥的（含 {@code KEY}、{@code TOKEN}、{@code SECRET}、
 * {@code PASSWORD}，不分大小写）和 {@code TANTALUM_} 开头的。照 DSH：子进程继承宿主的环境变量、去掉像凭据的
 * （{@code packages/mcp/mcp-client/src/transport.ts:15-23}）。没有沙箱以后只给几个变量会让常见命令跑不起来（{@code JAVA_HOME}、
 * Windows 的 {@code APPDATA} 这类），所以不再只给白名单。
 *
 * <p>{@code --env 名字} 是明确放行：名字像密钥的也给（例如 {@code GITHUB_TOKEN}）。{@code TANTALUM_TOKEN} 怎样都不给。
 */
public final class CommandEnv {

    /** 连接程序的令牌：任何情况下都不给子进程。 */
    public static final String TOKEN = "TANTALUM_TOKEN";

    private CommandEnv() {}

    /** 名字像密钥的环境变量。 */
    public static boolean secret(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        return upper.contains("KEY") || upper.contains("TOKEN") || upper.contains("SECRET") || upper.contains("PASSWORD");
    }

    /** 从 {@code host}（连接程序自己的环境变量）算出给子进程的；{@code allowed} 是 {@code --env} 给的名字。 */
    public static Map<String, String> of(Map<String, String> host, List<String> allowed) {
        Map<String, String> env = new LinkedHashMap<>();
        host.forEach((name, value) -> {
            if (value == null || name.equalsIgnoreCase(TOKEN)) {
                return;
            }
            boolean explicit = allowed.stream().anyMatch(name::equalsIgnoreCase);
            if (explicit || (!secret(name) && !name.toUpperCase(Locale.ROOT).startsWith("TANTALUM_"))) {
                env.put(name, value);
            }
        });
        return Map.copyOf(env);
    }
}
