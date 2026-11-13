package tantalum.shell.web;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tantalum.local.wire.Hello;
import tantalum.local.wire.Wire;
import tantalum.shell.boot.Engine;
import tantalum.shell.local.CommandApprovalHook;
import tantalum.shell.local.ConnectorJar;
import tantalum.shell.local.LocalLinks;
import tantalum.shell.local.LocalTokens;
import tantalum.value.id.Principal;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.NullNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 插件页的本机工作区卡片：连接状态和开关、生成令牌、撤销。只看、只改当前登录用户自己的；
 * 登录和 {@code Origin} 由 {@code SecurityConfig} 统一查。开关照用 {@code PUT /api/plugins/local/{name}}。
 * 令牌只在生成它的那次响应里出现，不进日志。
 */
@RestController
public class LocalController {

    /** 页面上能选的有效期。 */
    static final Map<String, Duration> VALIDITIES = Map.of(
            "30m", Duration.ofMinutes(30), "1h", Duration.ofHours(1), "4h", Duration.ofHours(4), "1d", Duration.ofDays(1));

    private final Engine engine;
    private final WebJson json;
    private final ConnectorJar connector;

    public LocalController(Engine engine, WebJson json,
                           @org.springframework.beans.factory.annotation.Value("${tantalum.local.connector-jar:}") String connectorJar) {
        this.engine = engine;
        this.json = json;
        this.connector = ConnectorJar.find(connectorJar, ConnectorJar.serverDir());
    }

    /**
     * 下载连接程序（connector-jar 需求 2.1）：服务器旁边那个 {@code tantalum-connect.jar}。要登录（{@code SecurityConfig} 统一查）。
     * 服务器上没放时 404，插件页第 1 步写原因。
     */
    @GetMapping("/api/local/connector.jar")
    ResponseEntity<?> download() {
        Optional<ConnectorJar.Info> info = connector.info();
        if (info.isEmpty()) {
            return json.error(ErrorCode.LOCAL_CONNECTOR_MISSING, "the connector jar is not on this server");
        }
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=" + ConnectorJar.NAME)
                .header("Content-Type", "application/java-archive")
                .contentLength(info.get().size())
                .body(new org.springframework.core.io.FileSystemResource(info.get().file()));
    }

    /**
     * 开关状态和这个用户现在的连接；没连上时 {@code link} 为 null。{@code tools} 是可以逐个勾的工具：子开关名 →
     * {@code [{name, enabled}]}，改和恢复默认走 {@code PUT/DELETE /api/plugins/local/<工具名>}。
     */
    @GetMapping("/api/local")
    ResponseEntity<String> status() {
        Principal user = WebJson.currentUser();
        ObjectNode response = json.object();
        response.set("switches", json.tree(engine.localWorkspace().switches(user)));
        ObjectNode tools = response.putObject("tools");
        engine.localWorkspace().choices(user).forEach((child, states) -> {
            var list = tools.putArray(child);
            states.forEach((name, enabled) -> list.addObject().put("name", name).put("enabled", enabled));
        });
        response.set("link", engine.localLinks().status(user)
                .map(status -> json.tree(link(status, connector, server -> engine.localWorkspace().mcpSwitch(user, server))))
                .orElse(NullNode.getInstance()));
        // 命令先问（connector-jar 需求 5.5）：内置的审批插件开没开；配置里整个去掉时不写
        if (engine.plugins().hasPlugin(CommandApprovalHook.NAME)) {
            response.put("commandApproval", engine.plugins().pluginEnabled(user, CommandApprovalHook.NAME));
        }
        // 能不能下载、哪个版本（connector-jar 需求 2）；不给服务器上的路径
        Map<String, Object> download = new LinkedHashMap<>();
        download.put("available", connector.info().isPresent());
        connector.info().ifPresent(info -> {
            download.put("version", info.version());
            download.put("sha256", info.sha256());
            download.put("size", info.size());
        });
        response.set("connector", json.tree(download));
        return json.json(200, response);
    }

    /**
     * 请求体 {@code {"validity": "30m" | "1h" | "4h" | "1d"}}。返回令牌、一行启动命令和到期时间。命令里的地址是浏览器访问的这个服务器；
     * 令牌放在环境变量里，不放在参数里。
     */
    @PostMapping("/api/local/tokens")
    ResponseEntity<String> issue(@RequestBody String body, HttpServletRequest request) {
        Optional<JsonNode> parsed = json.read(body);
        String validity = parsed.map(node -> WebJson.text(node, "validity")).orElse(null);
        if (validity == null || !VALIDITIES.containsKey(validity)) {
            return json.error(ErrorCode.REQUEST_INVALID, "validity must be one of 30m, 1h, 4h, 1d");
        }
        LocalTokens.Issued issued = engine.localLinks().tokens().issue(WebJson.currentUser(), VALIDITIES.get(validity));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("token", issued.token());
        // bash 的写法；插件页按看页面的电脑换成 PowerShell 的（connector-jar 需求 3.2）
        response.put("command", "TANTALUM_TOKEN=" + issued.token() + " java -jar tantalum-connect.jar --server " + server(request) + " --allow .");
        response.put("server", server(request));
        response.put("expiresAt", issued.expiresAt());
        return json.secret(200, response);
    }

    /** 撤销这个用户的全部令牌，断开连接。 */
    @DeleteMapping("/api/local/tokens")
    ResponseEntity<String> revoke() {
        engine.localLinks().revoke(WebJson.currentUser());
        return json.json(200, Map.of("revoked", true));
    }

    /** 浏览器访问的地址：反向代理后面时按代理传来的主机名（{@code server.forward-headers-strategy}）。 */
    private static String server(HttpServletRequest request) {
        String scheme = request.getScheme();
        int port = request.getServerPort();
        boolean standard = (scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443);
        return scheme + "://" + request.getServerName() + (standard ? "" : ":" + port) + request.getContextPath();
    }

    private static Map<String, Object> link(LocalLinks.Status status, ConnectorJar connector,
                                            java.util.function.Predicate<String> mcpEnabled) {
        Hello hello = status.hello();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("device", hello.device());
        view.put("roots", hello.roots());
        view.put("git", hello.git());
        view.put("write", hello.write());
        view.put("shell", hello.shell());
        view.put("network", hello.network());
        // 协议 5 起：电脑上的浏览器能不能用、是哪个、有没有窗口；旧的连接程序没有这一项
        if (hello.browser() != null) {
            view.put("browser", hello.browser());
        }
        // 协议 2 的连接程序不认会话目录：控制台据此提示“连接程序要更新”
        view.put("protocol", hello.protocol());
        view.put("current", hello.protocol() >= Wire.PROTOCOL);
        // 电脑上的是不是这里提供的那个 jar：不知道（服务器上没放）时不写
        connector.matches(hello).ifPresent(matches -> view.put("connectorMatches", matches));
        view.put("connector", hello.connector());
        // 本机 MCP 服务器（协议 6 起）：名字、状态、原因、工具名和说明；插件页列出来，每个服务器一个开关
        if (!hello.mcp().isEmpty()) {
            view.put("mcp", hello.mcp().stream().map(server -> {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("name", server.name());
                one.put("state", server.state().name().toLowerCase(java.util.Locale.ROOT));
                one.put("enabled", mcpEnabled.test(server.name()));
                if (server.reason() != null) {
                    one.put("reason", server.reason());
                }
                one.put("tools", server.tools().stream().map(tool -> Map.of("name", tool.name(), "description", tool.description())).toList());
                return one;
            }).toList());
        }
        view.put("connectedAt", status.connectedAt());
        view.put("expiresAt", status.expiresAt());
        return view;
    }
}
