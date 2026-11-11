package tantalum.shell.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tantalum.shell.access.PluginGate;
import tantalum.shell.boot.Engine;
import tantalum.shell.mcp.McpRegistry;
import tantalum.shell.param.ParamRules.InvalidParam;
import tantalum.shell.plugin.Kind;
import tantalum.shell.route.ModelInfo;
import tantalum.value.id.Principal;
import tantalum.value.json.Json;
import tools.jackson.databind.JsonNode;

/**
 * 插件页：当前登录用户自己的开关和参数，看不到也改不了别人的。本机工作区的开关和连接在 {@code /api/local}，
 * 联网的开关在这里的 {@code web} 一项，开关都用 {@code PUT /api/plugins/{kind}/{name}}。
 * 管理员定默认、锁定在 {@link PluginAdminController}。返回里没有密码的原文和密文。
 */
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
        // 打开的工具能不能在同一个会话的下一次提问就用上；OpenAI 兼容的路线直接换工具列表，总是可以
        response.put("midSessionChanges", engine.routes().describe(engine.routes().defaultChoice()).map(ModelInfo::toolChanges).orElse(true));
        response.put("tools", overview.tools().stream().map(tool -> tool(tool, true)).toList());
        response.put("mcp", overview.mcp().stream().map(server -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("name", server.name());
            view.put("state", states.getOrDefault(server.name(), "UNKNOWN"));
            view.put("enabled", server.enabled());
            view.put("byDefault", server.byDefault());
            view.put("changed", server.changed());
            PluginViews.lock(view, server.lock());
            // MCP 的工具没有参数（没有单独的一行放表单）
            view.put("tools", server.tools().stream().map(tool -> tool(tool, false)).toList());
            return view;
        }).toList());
        // 联网这一组：两项都被管理员关掉时照样返回，页面照样显示；不返回代理地址和凭据
        Map<String, Object> web = new LinkedHashMap<>();
        web.put("switches", engine.webAccess().switches(user));
        web.put("admin", engine.webAccess().admin());
        web.put("blockPrivateNetwork", engine.webAccess().blockPrivateNetwork());
        response.put("web", web);
        response.put("plugins", overview.plugins().stream().map(PluginController::plugin).toList());
        return json.json(200, response);
    }

    /** 请求体 {@code {"enabled": true}}。不认识的种类和名字 404，锁着的 403（带是谁锁的、为什么）。 */
    @PutMapping("/api/plugins/{kind}/{name}")
    ResponseEntity<String> set(@PathVariable String kind, @PathVariable String name, @RequestBody String body) {
        Optional<JsonNode> parsed = json.read(body);
        if (parsed.isEmpty() || !parsed.get().path("enabled").isBoolean()) {
            return json.error(ErrorCode.REQUEST_INVALID, "body must be {\"enabled\": true or false}");
        }
        boolean enabled = parsed.get().path("enabled").asBoolean();
        return change(kind, name, parsedKind -> engine.plugins().set(WebJson.currentUser(), parsedKind, name, enabled),
                parsedKind -> Map.of("kind", parsedKind.key(), "name", name, "enabled", enabled));
    }

    /** 开关恢复默认：忘掉我选过什么。 */
    @DeleteMapping("/api/plugins/{kind}/{name}")
    ResponseEntity<String> reset(@PathVariable String kind, @PathVariable String name) {
        return change(kind, name, parsedKind -> engine.plugins().reset(WebJson.currentUser(), parsedKind, name),
                parsedKind -> Map.of("kind", parsedKind.key(), "name", name));
    }

    /**
     * 请求体 {@code {"values": {键: 值}}}，只收归用户的键；密码键给了字符串就替换，没给不动。整份检查，有一个不合格一个都不存。
     * 值不合格、不认识的键 400，归管理员的键 403，开关锁着时带了普通参数 403。返回这一项现在的参数。
     */
    @PutMapping("/api/plugins/{kind}/{name}/params")
    ResponseEntity<String> saveParams(@PathVariable String kind, @PathVariable String name, @RequestBody String body) {
        Optional<Map<String, Json>> values = PluginViews.values(json, body, "values");
        if (values.isEmpty()) {
            // 不回显请求体：里面可能有密码
            return json.error(ErrorCode.REQUEST_INVALID, "body must be {\"values\": {key: value}}");
        }
        return changeParams(kind, name, parsedKind -> engine.plugins().saveParams(WebJson.currentUser(), parsedKind, name, values.get()));
    }

    /** 参数恢复默认：删掉我填的全部普通参数，密码不动。 */
    @DeleteMapping("/api/plugins/{kind}/{name}/params")
    ResponseEntity<String> resetParams(@PathVariable String kind, @PathVariable String name) {
        return changeParams(kind, name, parsedKind -> engine.plugins().resetParams(WebJson.currentUser(), parsedKind, name));
    }

    /** 清除一个密码。不是密码的键 400。 */
    @DeleteMapping("/api/plugins/{kind}/{name}/params/{key}")
    ResponseEntity<String> clearSecret(@PathVariable String kind, @PathVariable String name, @PathVariable String key) {
        return changeParams(kind, name, parsedKind -> engine.plugins().clearSecret(WebJson.currentUser(), parsedKind, name, key));
    }

    private ResponseEntity<String> changeParams(String kind, String name, Consumer<Kind> action) {
        return change(kind, name, action, parsedKind -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("kind", parsedKind.key());
            view.put("name", name);
            view.put("params", PluginViews.params(paramsOf(parsedKind, name)));
            return view;
        });
    }

    private ResponseEntity<String> change(String kind, String name, Consumer<Kind> action,
                                          java.util.function.Function<Kind, Map<String, Object>> answer) {
        Optional<Kind> parsedKind = PluginViews.kind(kind);
        if (parsedKind.isEmpty()) {
            return PluginViews.unknownKind(json, kind);
        }
        try {
            action.accept(parsedKind.get());
        } catch (PluginGate.UnknownPlugin unknown) {
            return PluginViews.unknown(json, unknown);
        } catch (PluginGate.LockedPlugin refused) {
            return PluginViews.locked(json, refused);
        } catch (PluginGate.ForbiddenParam forbidden) {
            return PluginViews.forbidden(json, forbidden);
        } catch (InvalidParam invalid) {
            return PluginViews.invalid(json, invalid);
        }
        return json.json(200, answer.apply(parsedKind.get()));
    }

    /** 这一项现在的参数（只有内置工具和钩子插件有）。 */
    private List<PluginGate.ParamView> paramsOf(Kind kind, String name) {
        PluginGate.Overview overview = engine.plugins().overview(WebJson.currentUser());
        if (kind == Kind.TOOL) {
            return overview.tools().stream().filter(tool -> tool.name().equals(name)).findFirst().map(PluginGate.ToolView::params).orElse(List.of());
        }
        return overview.plugins().stream().filter(plugin -> plugin.name().equals(name)).findFirst().map(PluginGate.PluginView::params)
                .orElse(List.of());
    }

    private static Map<String, Object> tool(PluginGate.ToolView tool, boolean withParams) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("name", tool.name());
        view.put("description", tool.description());
        view.put("enabled", tool.enabled());
        view.put("byDefault", tool.byDefault());
        view.put("changed", tool.changed());
        PluginViews.lock(view, tool.lock());
        if (withParams) {
            view.put("params", PluginViews.params(tool.params()));
        }
        return view;
    }

    private static Map<String, Object> plugin(PluginGate.PluginView plugin) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("name", plugin.name());
        view.put("description", plugin.description().wire());
        view.put("points", plugin.points());
        view.put("group", plugin.group());
        view.put("enabled", plugin.enabled());
        view.put("byDefault", plugin.byDefault());
        view.put("changed", plugin.changed());
        PluginViews.lock(view, plugin.lock());
        view.put("params", PluginViews.params(plugin.params()));
        return view;
    }
}
