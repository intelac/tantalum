package tantalum.shell.web;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tantalum.shell.access.PluginGate;
import tantalum.shell.auth.UserStore;
import tantalum.shell.boot.Engine;
import tantalum.shell.param.ParamRules.InvalidParam;
import tantalum.shell.plugin.Kind;
import tantalum.value.id.Principal;
import tantalum.value.json.Json;
import tools.jackson.databind.JsonNode;

/**
 * 管理员管插件：给所有人定开关的默认和锁定、参数的默认值，给一个人指定只归管理员的参数（比如每日额度）。
 * 只有管理员能调（{@code SecurityConfig} 对 {@code /api/admin/**} 要 {@code ROLE_ADMIN}，否则 403）。
 * 管理员看不到别人自己填的参数，也看不到任何密码。
 */
@RestController
public class PluginAdminController {

    /** 锁定原因的最长字数。 */
    static final int REASON_MAX = 500;

    private final Engine engine;
    private final UserStore users;
    private final WebJson json;

    public PluginAdminController(Engine engine, UserStore users, WebJson json) {
        this.engine = engine;
        this.users = users;
        this.json = json;
    }

    /** 所有能定默认的开关：自带的默认、管理员定的默认（没定就没有 {@code byAdmin}）、锁、参数的默认值（不含密码）。 */
    @GetMapping("/api/admin/plugins")
    ResponseEntity<String> list() {
        return json.json(200, Map.of("items", engine.plugins().defaults().stream().map(PluginAdminController::view).toList()));
    }

    /**
     * 请求体 {@code {"enabled"?: bool 或 null, "locked"?: bool, "reason"?: string, "params"?: {键: 值 或 null}}}，给了哪个改哪个；
     * {@code null} 是清掉、回到自带的。代码或配置里定了必须开启的，开关和锁改不了（403），参数的默认值照样能改。
     */
    @PutMapping("/api/admin/plugins/{kind}/{name}")
    ResponseEntity<String> set(@PathVariable String kind, @PathVariable String name, @RequestBody String body) {
        Optional<JsonNode> parsed = json.read(body).filter(JsonNode::isObject);
        Optional<Kind> parsedKind = PluginViews.kind(kind);
        if (parsedKind.isEmpty()) {
            return PluginViews.unknownKind(json, kind);
        }
        if (parsed.isEmpty() || !wellFormed(parsed.get())) {
            return json.error(ErrorCode.REQUEST_INVALID,
                    "body must be {\"enabled\"?: bool or null, \"locked\"?: bool, \"reason\"?: string, \"params\"?: {key: value or null}}");
        }
        JsonNode node = parsed.get();
        boolean switches = node.has("enabled") || node.has("locked") || node.has("reason");
        PluginGate gate = engine.plugins();
        try {
            PluginGate.Lock lock = gate.lock(parsedKind.get(), name);
            if (switches && lock != null && !PluginGate.Lock.ADMIN.equals(lock.source())) {
                return PluginViews.locked(json, name, lock);
            }
            // 先存参数：它整份检查完才写；后面改开关只可能因为名字不存在失败，那时参数也没写
            if (node.has("params") && !node.get("params").isEmpty()) {
                gate.saveDefaults(parsedKind.get(), name, PluginViews.values(json, node.get("params")));
            }
            if (switches) {
                Optional<PluginGate.DefaultView> now = gate.defaults().stream()
                        .filter(item -> item.kind() == parsedKind.get() && item.name().equals(name)).findFirst();
                Boolean enabled = node.has("enabled") ? (node.get("enabled").isNull() ? null : (Boolean) node.get("enabled").asBoolean())
                        : now.map(PluginGate.DefaultView::byAdmin).orElse(null);
                boolean locked = node.has("locked") ? node.get("locked").asBoolean() : now.map(item -> item.lock() != null).orElse(false);
                String reason = node.has("reason") ? node.get("reason").asString()
                        : now.map(PluginGate.DefaultView::lock).map(PluginGate.Lock::reason).orElse("");
                gate.setDefault(parsedKind.get(), name, enabled, locked, reason);
            }
        } catch (PluginGate.UnknownPlugin unknown) {
            return PluginViews.unknown(json, unknown);
        } catch (PluginGate.LockedPlugin refused) {
            return PluginViews.locked(json, refused);
        } catch (PluginGate.PersonalPlugin personal) {
            return json.error(ErrorCode.PLUGIN_PERSONAL, personal.getMessage(), Map.of("name", personal.name()));
        } catch (PluginGate.ForbiddenParam forbidden) {
            return PluginViews.forbidden(json, forbidden);
        } catch (InvalidParam invalid) {
            return PluginViews.invalid(json, invalid);
        }
        return gate.defaults().stream().filter(item -> item.kind() == parsedKind.get() && item.name().equals(name)).findFirst()
                .map(item -> json.json(200, view(item)))
                .orElseGet(() -> json.error(ErrorCode.PLUGIN_NOT_FOUND, "no such " + parsedKind.get().key(), Map.of("name", name)));
    }

    private static boolean wellFormed(JsonNode node) {
        return (!node.has("enabled") || node.get("enabled").isBoolean() || node.get("enabled").isNull())
                && (!node.has("locked") || node.get("locked").isBoolean())
                && (!node.has("reason") || node.get("reason").isString() && node.get("reason").asString().length() <= REASON_MAX)
                && (!node.has("params") || node.get("params").isObject())
                && (node.has("enabled") || node.has("locked") || node.has("reason") || node.has("params"));
    }

    /** 这个人的只归管理员的参数：每项现在的生效值和来源。没有这个用户 404。 */
    @GetMapping("/api/admin/users/{name}/params")
    ResponseEntity<String> assigned(@PathVariable String name) throws SQLException {
        if (users.status(name).isEmpty()) {
            return json.error(ErrorCode.USER_NOT_FOUND, "no such user");
        }
        return json.json(200, assignedView(name));
    }

    /** 请求体 {@code {"values": {键: 值 或 null}}}，只收归管理员的键；{@code null} 是清掉、回到默认。 */
    @PutMapping("/api/admin/users/{name}/params/{kind}/{plugin}")
    ResponseEntity<String> assign(@PathVariable String name, @PathVariable String kind, @PathVariable String plugin,
                                  @RequestBody String body) throws SQLException {
        if (users.status(name).isEmpty()) {
            return json.error(ErrorCode.USER_NOT_FOUND, "no such user");
        }
        Optional<Kind> parsedKind = PluginViews.kind(kind);
        if (parsedKind.isEmpty()) {
            return PluginViews.unknownKind(json, kind);
        }
        Optional<Map<String, Json>> values = PluginViews.values(json, body, "values");
        if (values.isEmpty()) {
            return json.error(ErrorCode.REQUEST_INVALID, "body must be {\"values\": {key: value or null}}");
        }
        try {
            engine.plugins().assign(new Principal(name), parsedKind.get(), plugin, values.get());
        } catch (PluginGate.UnknownPlugin unknown) {
            return PluginViews.unknown(json, unknown);
        } catch (PluginGate.ForbiddenParam forbidden) {
            return PluginViews.forbidden(json, forbidden);
        } catch (InvalidParam invalid) {
            return PluginViews.invalid(json, invalid);
        }
        return json.json(200, assignedView(name));
    }

    private Map<String, Object> assignedView(String name) {
        List<Map<String, Object>> items = engine.plugins().assigned(new Principal(name)).stream().map(item -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("kind", item.kind().key());
            view.put("name", item.name());
            view.put("params", PluginViews.params(item.params()));
            return view;
        }).toList();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("user", name);
        view.put("items", items);
        return view;
    }

    private static Map<String, Object> view(PluginGate.DefaultView item) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("kind", item.kind().key());
        view.put("name", item.name());
        view.put("group", item.group());
        view.put("builtin", item.builtin());
        if (item.byAdmin() != null) {
            view.put("byAdmin", item.byAdmin());
        }
        PluginViews.lock(view, item.lock());
        view.put("params", PluginViews.params(item.params()));
        return view;
    }
}
