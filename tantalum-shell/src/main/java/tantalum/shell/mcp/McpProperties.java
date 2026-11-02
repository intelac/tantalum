package tantalum.shell.mcp;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** MCP 服务器的原始配置，只由管理员配置。没配时可以整个为 null。校验在 {@link McpSettings}。 */
public record McpProperties(Map<String, Server> servers, Duration startupWait) {

    /** 一个远程 MCP 服务器。凭据只从 {@code tokenEnv} 指向的环境变量读取；数字和时长为空时用默认值。 */
    public record Server(String url, String auth, String tokenEnv, List<String> allow, List<String> deny,
                         Integer concurrency, Duration timeout, Integer maxResultBytes, Integer maxTools) {}
}
