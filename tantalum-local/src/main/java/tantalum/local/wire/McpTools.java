package tantalum.local.wire;

import java.util.Objects;

/**
 * 电脑→服务器（协议 6 起）：一个本机 MCP 服务器的最新状态和工具，整份替换上一份。启动好了、{@code list_changed}、重连、
 * 崩溃、重连次数用完时都发。反方向没有对应的消息：电脑上启动什么只由那台电脑上的文件和参数决定（connector-jar 需求 6.8）。
 */
public record McpTools(McpServerState server) implements Message {

    public McpTools {
        Objects.requireNonNull(server, "server");
    }
}
