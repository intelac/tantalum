package tantalum.connect.mcp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 电脑上的 {@code mcp.json}（connector-jar 需求 6.1）：格式同 Claude Desktop 和 pi 的 {@code mcpServers}
 * （pi {@code extensions/mcp/config.js:4-16,56-108}）：
 * <pre>{"mcpServers": {"名字": {"command": "npx", "args": ["-y", "…"], "env": {"K": "v"}, "cwd": "…", "disabled": false}}}</pre>
 * 名字只能是 {@code [A-Za-z0-9_-]{1,32}}（DSH {@code packages/mcp/mcp-client/src/index.ts:55}）。每一项的错误单独记下，不影响别的。
 * 文件不存在当作空的。
 */
public record McpConfig(List<Entry> servers, List<String> problems) {

    static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public McpConfig {
        servers = List.copyOf(servers);
        problems = List.copyOf(problems);
    }

    /** 一个 MCP 服务器怎么启动。{@code cwd} 为 null 时用连接程序的主目录。 */
    public record Entry(String name, String command, List<String> args, Map<String, String> env, Path cwd, boolean disabled) {

        public Entry {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(command, "command");
            args = List.copyOf(args);
            env = Map.copyOf(env);
        }
    }

    public static McpConfig empty() {
        return new McpConfig(List.of(), List.of());
    }

    /** 默认位置：macOS、Linux 上 {@code $XDG_CONFIG_HOME/tantalum/mcp.json}（默认 {@code ~/.config}），Windows 上 {@code %APPDATA%\tantalum\mcp.json}。 */
    public static Path defaultPath(Map<String, String> env, String osName, Path home) {
        if (osName.toLowerCase(java.util.Locale.ROOT).startsWith("windows")) {
            String appData = env.get("APPDATA");
            return (appData == null ? home.resolve("AppData").resolve("Roaming") : Path.of(appData)).resolve("tantalum").resolve("mcp.json");
        }
        String xdg = env.get("XDG_CONFIG_HOME");
        return (xdg == null || xdg.isBlank() ? home.resolve(".config") : Path.of(xdg)).resolve("tantalum").resolve("mcp.json");
    }

    /** 读文件；不存在是空的，读不了或不是 JSON 时没有服务器、写明原因。 */
    public static McpConfig read(Path file) {
        if (!Files.exists(file)) {
            return empty();
        }
        try {
            return parse(Files.readString(file));
        } catch (IOException failure) {
            return new McpConfig(List.of(), List.of("cannot read " + file + ": " + failure.getMessage()));
        }
    }

    static McpConfig parse(String text) {
        JsonNode root;
        try {
            root = JSON.readTree(text);
        } catch (JacksonException failure) {
            return new McpConfig(List.of(), List.of("not valid JSON: " + failure.getOriginalMessage()));
        }
        JsonNode servers = root == null ? null : root.get("mcpServers");
        if (servers == null || servers.isNull()) {
            return empty();
        }
        if (!servers.isObject()) {
            return new McpConfig(List.of(), List.of("mcpServers must be an object"));
        }
        List<Entry> entries = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, JsonNode> field : servers.properties()) {
            String name = field.getKey();
            JsonNode server = field.getValue();
            if (!NAME.matcher(name).matches()) {
                problems.add("server \"" + name + "\": the name may only use letters, digits, '_' and '-', at most 32 characters");
                continue;
            }
            if (!server.isObject()) {
                problems.add("server \"" + name + "\": must be an object");
                continue;
            }
            JsonNode command = server.get("command");
            if (command == null || !command.isString() || command.asString().isBlank()) {
                problems.add("server \"" + name + "\": \"command\" is missing (only local programs over stdio are supported)");
                continue;
            }
            List<String> args = new ArrayList<>();
            JsonNode argsNode = server.get("args");
            if (argsNode != null && !argsNode.isNull()) {
                if (!argsNode.isArray()) {
                    problems.add("server \"" + name + "\": \"args\" must be a list of strings");
                    continue;
                }
                argsNode.forEach(arg -> args.add(arg.asString()));
            }
            Map<String, String> env = new LinkedHashMap<>();
            JsonNode envNode = server.get("env");
            if (envNode != null && !envNode.isNull()) {
                if (!envNode.isObject()) {
                    problems.add("server \"" + name + "\": \"env\" must be an object of strings");
                    continue;
                }
                envNode.properties().forEach(entry -> env.put(entry.getKey(), entry.getValue().asString()));
            }
            JsonNode cwd = server.get("cwd");
            JsonNode disabled = server.get("disabled");
            entries.add(new Entry(name, command.asString(), args, env,
                    cwd == null || cwd.isNull() || cwd.asString().isBlank() ? null : Path.of(cwd.asString()),
                    disabled != null && disabled.asBoolean(false)));
        }
        return new McpConfig(entries, problems);
    }

    /** 加一项（内置的 {@code playwright}）；文件里已有同名的就以文件为准。 */
    public McpConfig withDefault(Entry entry) {
        if (servers.stream().anyMatch(existing -> existing.name().equals(entry.name()))) {
            return this;
        }
        List<Entry> all = new ArrayList<>(servers);
        all.add(entry);
        return new McpConfig(all, problems);
    }
}
