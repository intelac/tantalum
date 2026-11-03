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
import tantalum.shell.mcp.McpRegistry;
import tantalum.value.id.Principal;
import tools.jackson.databind.JsonNode;

/** 插件页（core-tools 需求 7）：当前登录用户自己的开关，看不到也改不了别人的。 */
@RestController
public class PluginController {

    private final Engine engine;
    private final WebJson json;

    public PluginController(Engine engine, WebJson json) {
        this.engine = engine;
        this.json = json;
    }

    @GetMapping("/api/plugins")
    ResponseEntity<String> list() {
        Principal user = WebJson.currentUser();
        PluginGate.Overview overview = engine.plugins().overview(user);
        Map<String, String> states = engine.mcp().servers().stream()
                .collect(Collectors.toMap(McpRegistry.ServerStatus::name, McpRegistry.ServerStatus::state));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("tools", overview.tools().stream().map(PluginController::tool).toList());
        response.put("mcp", overview.mcp().stream().map(server -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("name", server.name());
            view.put("state", states.getOrDefault(server.name(), "UNKNOWN"));
            view.put("enabled", server.enabled());
            view.put("tools", server.tools().stream().map(PluginController::tool).toList());
            return view;
        }).toList());
        response.put("plugins", overview.plugins().stream().map(plugin -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("name", plugin.name());
            view.put("description", plugin.description());
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
            return json.error(400, "body must be {\"enabled\": true or false}");
        }
        Kind parsedKind;
        try {
            parsedKind = Kind.of(kind);
        } catch (IllegalArgumentException unknown) {
            return json.error(404, "no such kind: " + kind);
        }
        boolean enabled = parsed.get().path("enabled").asBoolean();
        try {
            engine.plugins().set(WebJson.currentUser(), parsedKind, name, enabled);
        } catch (PluginGate.UnknownPlugin unknown) {
            return json.error(404, unknown.getMessage());
        } catch (IllegalArgumentException refused) {
            return json.error(400, refused.getMessage());
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
