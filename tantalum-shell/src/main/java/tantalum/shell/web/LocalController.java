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
import tantalum.shell.boot.Engine;
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

    public LocalController(Engine engine, WebJson json) {
        this.engine = engine;
        this.json = json;
    }

    /** 开关状态和这个用户现在的连接；没连上时 {@code link} 为 null。 */
    @GetMapping("/api/local")
    ResponseEntity<String> status() {
        Principal user = WebJson.currentUser();
        ObjectNode response = json.object();
        response.set("switches", json.tree(engine.localWorkspace().switches(user)));
        response.set("link", engine.localLinks().status(user).map(status -> json.tree(link(status))).orElse(NullNode.getInstance()));
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
        response.put("command", "TANTALUM_TOKEN=" + issued.token() + " tantalum-connect --server " + server(request) + " --allow .");
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

    private static Map<String, Object> link(LocalLinks.Status status) {
        Hello hello = status.hello();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("device", hello.device());
        view.put("roots", hello.roots());
        view.put("git", hello.git());
        view.put("write", hello.write());
        view.put("shell", hello.shell());
        view.put("network", hello.network());
        view.put("connectedAt", status.connectedAt());
        view.put("expiresAt", status.expiresAt());
        return view;
    }
}
