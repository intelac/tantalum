package tantalum.shell.web;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tantalum.shell.access.PluginGate;
import tantalum.shell.access.PluginSwitches.Kind;
import tantalum.shell.boot.Engine;
import tantalum.shell.boot.TantalumProperties;
import tantalum.shell.mcp.McpRegistry;
import tantalum.shell.route.ModelInfo;
import tantalum.value.id.Principal;
import tools.jackson.databind.JsonNode;

/**
 * 插件页：当前登录用户自己的开关，看不到也改不了别人的。本机工作区的开关和连接在 {@code /api/local}，
 * 联网的开关在这里的 {@code web} 一项，开关都用 {@code PUT /api/plugins/{kind}/{name}}。
 */
@RestController
public class PluginController {

    private final Engine engine;
    private final TantalumProperties properties;
    private final WebJson json;

    public PluginController(Engine engine, TantalumProperties properties, WebJson json) {
        this.engine = engine;
        this.properties = properties;
        this.json = json;
    }

    @GetMapping("/api/plugins")
    ResponseEntity<String> list() {
        Principal user = WebJson.currentUser();
        PluginGate.Overview overview = engine.plugins().overview(user);
        Map<String, String> states = engine.mcp().servers().stream()
                .collect(Collectors.toMap(McpRegistry.ServerStatus::name, McpRegistry.ServerStatus::state));
        Map<String, Object> response = new LinkedHashMap<>();
        // 打开的工具能不能在同一个会话的下一次提问就用上；OpenAI 兼容的路线直接换工具列表，总是可以
        response.put("midSessionChanges", engine.routes().describe(engine.routes().defaultChoice()).map(ModelInfo::toolChanges).orElse(true));
        response.put("tools", overview.tools().stream().map(PluginController::tool).toList());
        response.put("mcp", overview.mcp().stream().map(server -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("name", server.name());
            view.put("state", states.getOrDefault(server.name(), "UNKNOWN"));
            view.put("enabled", server.enabled());
            view.put("tools", server.tools().stream().map(PluginController::tool).toList());
            return view;
        }).toList());
        // 联网这一组：两项都被管理员关掉时照样返回，页面照样显示；不返回代理地址和凭据
        Map<String, Object> web = new LinkedHashMap<>();
        web.put("switches", engine.webAccess().switches(user));
        web.put("admin", engine.webAccess().admin());
        web.put("blockPrivateNetwork", engine.webAccess().blockPrivateNetwork());
        response.put("web", web);
        response.put("plugins", overview.plugins().stream().map(plugin -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("name", plugin.name());
            view.put("description", plugin.description().wire());
            view.put("points", plugin.points());
            view.put("mandatory", plugin.mandatory());
            view.put("enabled", plugin.enabled());
            return view;
        }).toList());
        return json.json(200, response);
    }

    /** 请求体 {@code {"enabled": true}}。不认识的种类和名字 404，必须开启的插件 400。 */
    @PutMapping("/api/plugins/{kind}/{name}")
    ResponseEntity<String> set(@PathVariable String kind, @PathVariable String name, @RequestBody String body) {
        Optional<JsonNode> parsed = json.read(body);
        if (parsed.isEmpty() || !parsed.get().path("enabled").isBoolean()) {
            return json.error(ErrorCode.REQUEST_INVALID, "body must be {\"enabled\": true or false}");
        }
        Kind parsedKind;
        try {
            parsedKind = Kind.of(kind);
        } catch (IllegalArgumentException unknown) {
            return json.error(ErrorCode.PLUGIN_NOT_FOUND, "no such kind: " + kind, Map.of("name", kind));
        }
        boolean enabled = parsed.get().path("enabled").asBoolean();
        try {
            engine.plugins().set(WebJson.currentUser(), parsedKind, name, enabled);
        } catch (PluginGate.UnknownPlugin unknown) {
            return json.error(ErrorCode.PLUGIN_NOT_FOUND, unknown.getMessage(), Map.of("name", unknown.name()));
        } catch (PluginGate.RequiredPlugin refused) {
            return json.error(ErrorCode.PLUGIN_REQUIRED, refused.getMessage(), Map.of("name", refused.name()));
        }
        return json.json(200, Map.of("kind", parsedKind.key(), "name", name, "enabled", enabled));
    }

    private static Map<String, Object> tool(PluginGate.ToolView tool) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("name", tool.name());
        view.put("description", tool.description());
        view.put("enabled", tool.enabled());
        return view;
    }
}
