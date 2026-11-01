package tantalum.shell.mcp;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import tantalum.shell.boot.TantalumProperties;

/** 校验过的 MCP 配置。任何一项不合法都让启动失败，错误里只有配置项名字，没有凭据内容。 */
public record McpSettings(List<Server> servers, Duration startupWait) {

    public enum Auth { NONE, SERVICE }

    /** 一个服务器。{@code token} 只在内存里，toString 不输出它。 */
    public record Server(String name, URI url, Auth auth, String token, Set<String> allow, Set<String> deny,
                         int concurrency, Duration timeout, int maxResultBytes, int maxTools) {
        @Override
        public String toString() {
            return "Server[" + name + " " + url + " " + auth + "]";
        }
    }

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    static final int DEFAULT_CONCURRENCY = 4;
    static final int DEFAULT_MAX_RESULT_BYTES = 64 * 1024;
    static final int DEFAULT_MAX_TOOLS = 64;

    public static McpSettings none() {
        return new McpSettings(List.of(), Duration.ZERO);
    }

    public boolean isEmpty() {
        return servers.isEmpty();
    }

    public static McpSettings from(TantalumProperties.Mcp mcp, Duration toolTimeout, UnaryOperator<String> environment) {
        if (mcp == null || mcp.servers() == null) {
            return none();
        }
        List<Server> servers = new ArrayList<>();
        new java.util.TreeMap<>(mcp.servers()).forEach((name, config) -> servers.add(server(name, config, toolTimeout, environment)));
        return new McpSettings(List.copyOf(servers), mcp.startupWait() == null ? Duration.ofSeconds(10) : mcp.startupWait());
    }

    private static Server server(String name, TantalumProperties.McpServer config, Duration toolTimeout, UnaryOperator<String> environment) {
        String key = "tantalum.mcp.servers." + name;
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(key + ": server name must match " + NAME.pattern());
        }
        Auth auth = auth(key, config.auth());
        String token = null;
        if (auth == Auth.SERVICE) {
            if (config.tokenEnv() == null || config.tokenEnv().isBlank()) {
                throw new IllegalArgumentException(key + ".token-env is required for auth=service");
            }
            token = environment.apply(config.tokenEnv());
            if (token == null || token.isBlank()) {
                throw new IllegalArgumentException(key + ".token-env: environment variable " + config.tokenEnv() + " is not set");
            }
        }
        URI url = url(key, config.url(), auth);
        Duration timeout = Objects.requireNonNullElse(config.timeout(), toolTimeout);
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(toolTimeout) > 0) {
            throw new IllegalArgumentException(key + ".timeout must be positive and at most tantalum.tool-timeout (" + toolTimeout + ")");
        }
        return new Server(name, url, auth, token, names(config.allow()), names(config.deny()),
                positive(key + ".concurrency", config.concurrency(), DEFAULT_CONCURRENCY), timeout,
                positive(key + ".max-result-bytes", config.maxResultBytes(), DEFAULT_MAX_RESULT_BYTES),
                positive(key + ".max-tools", config.maxTools(), DEFAULT_MAX_TOOLS));
    }

    private static Auth auth(String key, String value) {
        if (value == null || value.isBlank() || value.equals("none")) {
            return Auth.NONE;
        }
        if (value.equals("service")) {
            return Auth.SERVICE;
        }
        throw new IllegalArgumentException(key + ".auth must be none or service");
    }

    private static URI url(String key, String value, Auth auth) {
        URI url;
        try {
            url = URI.create(value == null ? "" : value);
        } catch (IllegalArgumentException bad) {
            throw new IllegalArgumentException(key + ".url is not a valid URL");
        }
        String scheme = url.getScheme();
        if (url.getHost() == null || !("http".equals(scheme) || "https".equals(scheme))) {
            throw new IllegalArgumentException(key + ".url must be an http or https URL");
        }
        if (url.getUserInfo() != null) {
            throw new IllegalArgumentException(key + ".url must not contain credentials; use token-env");
        }
        if (auth == Auth.SERVICE && scheme.equals("http") && !isLoopback(url.getHost())) {
            throw new IllegalArgumentException(key + ".url: credentials must not be sent over plain http to a non-loopback host");
        }
        return url;
    }

    /** 只认字面量，不走 DNS：DNS 结果不可信，也不能让校验发网络请求（pi 相同）。 */
    static boolean isLoopback(String host) {
        return host.equals("localhost") || host.equals("[::1]") || host.equals("::1")
                || host.matches("127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");
    }

    private static Set<String> names(List<String> names) {
        return names == null ? Set.of() : Set.copyOf(names);
    }

    private static int positive(String key, Integer value, int fallback) {
        if (value == null) {
            return fallback;
        }
        if (value <= 0) {
            throw new IllegalArgumentException(key + " must be positive");
        }
        return value;
    }
}
