package tantalum.shell.mcp;

import io.modelcontextprotocol.client.transport.McpHttpClientTransportAuthorizationException;
import io.modelcontextprotocol.spec.McpTransportSessionNotFoundException;

/** 从 SDK 抛出的异常判断 MCP 调用为什么失败。纯判断，不碰连接。 */
final class McpFailures {

    private McpFailures() {}

    /** SDK 2.1.0 把 404 变成这条没有原因的 RuntimeException（{@code McpClientSession.dismissPendingResponses}），只能靠消息识别。 */
    static boolean isSessionLost(Throwable failure) {
        return has(failure, McpTransportSessionNotFoundException.class)
                || failure.getMessage() != null && failure.getMessage().contains("session with server terminated");
    }

    static boolean isInterrupt(Throwable failure) {
        return Thread.currentThread().isInterrupted() || has(failure, InterruptedException.class);
    }

    static boolean isUnauthorized(Throwable failure) {
        return has(failure, McpHttpClientTransportAuthorizationException.class);
    }

    static boolean has(Throwable failure, Class<? extends Throwable> type) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
            if (cause.getCause() == cause) {
                return false;
            }
        }
        return false;
    }

    /** 只给类别，不给消息原文。 */
    static String category(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName();
    }
}
