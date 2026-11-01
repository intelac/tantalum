package tantalum.shell.mcp;

import java.util.Optional;
import java.util.regex.Pattern;

/** 纯函数：哪些工具对模型可见，以及它们对外的名字。{@code readOnlyHint} 是服务器自己声明的，放行前管理员要确认服务器可信。 */
final class McpToolFilter {

    private static final Pattern PUBLIC_NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private McpToolFilter() {}

    /** 顺序：deny 拒绝；allow 放行；声明只读放行；其余（包括没声明）拒绝。 */
    static boolean allowed(McpSettings.Server server, String tool, Boolean readOnlyHint) {
        if (server.deny().contains(tool)) {
            return false;
        }
        return server.allow().contains(tool) || Boolean.TRUE.equals(readOnlyHint);
    }

    /** {@code mcp__<server>__<tool>}。超长或含模型 API 不接受的字符时为空。 */
    static Optional<String> publicName(String server, String tool) {
        String name = "mcp__" + server + "__" + tool;
        return PUBLIC_NAME.matcher(name).matches() ? Optional.of(name) : Optional.empty();
    }
}
