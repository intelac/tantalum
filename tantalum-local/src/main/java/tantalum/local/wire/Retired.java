package tantalum.local.wire;

/**
 * 不再用的消息，收到时什么也不做。现在只有 {@code mcpTools}：11-14 之前下载的协议 6 连接程序会发它报本机 MCP 服务器，
 * remove-generic-mcp 去掉了本机 MCP。服务器解不开的消息会断开连接，所以这里接住它，旧 jar 照样能用（需求 3.2）。
 * 下次协议版本加 1、不再接受 6 时一起删。
 */
public record Retired() implements Message {}
