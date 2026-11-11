package tantalum.shell.plugin;

import java.util.Locale;

/**
 * 开关的种类：一个工具、一个 MCP 服务器（关掉等于关掉它的全部工具）、一个钩子插件、本机工作区或联网的总开关和子开关。
 * 参数只有工具和钩子插件有。
 */
public enum Kind {
    TOOL, MCP, PLUGIN, LOCAL, WEB;

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** 不认识时抛 {@link IllegalArgumentException}。 */
    public static Kind of(String key) {
        return valueOf(key.toUpperCase(Locale.ROOT));
    }
}
