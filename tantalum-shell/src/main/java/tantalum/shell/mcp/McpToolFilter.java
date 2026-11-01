package tantalum.shell.mcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** 纯函数：哪些工具对模型可见，以及它们对外的名字。{@code readOnlyHint} 是服务器自己声明的，放行前管理员要确认服务器可信。 */
final class McpToolFilter {

    private static final Pattern PUBLIC_NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private static final int MAX_NAME = 64;

    private McpToolFilter() {}

    /** 顺序：deny 拒绝；allow 放行；声明只读放行；其余（包括没声明）拒绝。 */
    static boolean allowed(McpSettings.Server server, String tool, Boolean readOnlyHint) {
        if (server.deny().contains(tool)) {
            return false;
        }
        return server.allow().contains(tool) || Boolean.TRUE.equals(readOnlyHint);
    }

    /** {@code mcp__<server>__<tool>}。超长或含模型 API 不接受的字符时为空。 */
    /**
     * {@code mcp__<server>__<tool>}。超长或含非法字符时替换并加原名的 SHA-256 前缀，不隐藏工具（pi、DSH 相同）。
     * 结果只取决于原名，重启后不变；调用时仍用原名。
     */
    static String publicName(String server, String tool) {
        String name = "mcp__" + server + "__" + tool;
        if (PUBLIC_NAME.matcher(name).matches()) {
            return name;
        }
        String clean = name.replaceAll("[^A-Za-z0-9_-]", "_");
        String suffix = "_" + HexFormat.of().formatHex(sha256(tool), 0, 4);
        int room = MAX_NAME - suffix.length();
        return (clean.length() > room ? clean.substring(0, room) : clean) + suffix;
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
