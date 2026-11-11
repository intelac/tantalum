package tantalum.shell.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.http.ResponseEntity;
import tantalum.shell.access.PluginGate;
import tantalum.shell.param.ParamRules.InvalidParam;
import tantalum.shell.plugin.Kind;
import tantalum.shell.plugin.Param;
import tantalum.value.json.Json;
import tools.jackson.databind.JsonNode;

/** 插件页两个接口（用户的、管理员的）共用的写法：锁、参数怎么写成 JSON，请求里的值怎么读，拒绝怎么回。 */
final class PluginViews {

    /** 错误里可以带回去的键：只有长得像参数键的。别的（可能是填错位置的内容）不带。 */
    private static final Pattern SAFE_KEY = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private PluginViews() {}

    /** 没锁时不写 {@code lock}。 */
    static void lock(Map<String, Object> view, PluginGate.Lock lock) {
        if (lock != null) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("source", lock.source());
            out.put("reason", lock.reason());
            view.put("lock", out);
        }
    }

    static List<Map<String, Object>> params(List<PluginGate.ParamView> params) {
        return params.stream().map(PluginViews::param).toList();
    }

    /** 密码没有 {@code value} 和 {@code source}，只有存没存、解不解得开。 */
    private static Map<String, Object> param(PluginGate.ParamView item) {
        Param param = item.param();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("key", param.key());
        view.put("type", param.type().name().toLowerCase(Locale.ROOT));
        view.put("label", param.label().wire());
        view.put("hint", param.hint().wire());
        view.put("owner", param.owner().name().toLowerCase(Locale.ROOT));
        if (param.min() != null) {
            view.put("min", param.min());
        }
        if (param.max() != null) {
            view.put("max", param.max());
        }
        if (param.secret()) {
            view.put("set", item.set());
            view.put("stale", item.stale());
        } else {
            view.put("value", item.value());
            view.put("source", item.source());
        }
        view.put("editable", item.editable());
        return view;
    }

    /** 不认识的种类为空。 */
    static Optional<Kind> kind(String kind) {
        try {
            return Optional.of(Kind.of(kind));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }

    /** 请求体里 {@code field} 这个对象的每一项；请求体不是 JSON、没有这个对象时为空。 */
    static Optional<Map<String, Json>> values(WebJson json, String body, String field) {
        return json.read(body).map(node -> node.path(field)).filter(JsonNode::isObject).map(node -> values(json, node));
    }

    static Map<String, Json> values(WebJson json, JsonNode object) {
        Map<String, Json> values = new LinkedHashMap<>();
        object.properties().forEach(entry -> values.put(entry.getKey(), json.value(entry.getValue())));
        return values;
    }

    // ---- 拒绝 ----

    static ResponseEntity<String> unknownKind(WebJson json, String kind) {
        return json.error(ErrorCode.PLUGIN_NOT_FOUND, "no such kind", Map.of("name", SAFE_KEY.matcher(kind).matches() ? kind : ""));
    }

    static ResponseEntity<String> unknown(WebJson json, PluginGate.UnknownPlugin unknown) {
        return json.error(ErrorCode.PLUGIN_NOT_FOUND, unknown.getMessage(), Map.of("name", unknown.name()));
    }

    static ResponseEntity<String> locked(WebJson json, PluginGate.LockedPlugin refused) {
        return locked(json, refused.name(), refused.lock());
    }

    static ResponseEntity<String> locked(WebJson json, String name, PluginGate.Lock lock) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", name);
        params.put("source", lock.source());
        if (!lock.reason().isEmpty()) {
            params.put("reason", lock.reason());
        }
        return json.error(ErrorCode.PLUGIN_LOCKED, name + " is locked (" + lock.source() + ")", params);
    }

    /** 诊断和参数里都没有值；键只在它是声明过的键时带回去——不认识的键是请求里来的，可能就是填错位置的密码。 */
    static ResponseEntity<String> invalid(WebJson json, InvalidParam invalid) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("reason", invalid.reason());
        if (!"unknown".equals(invalid.reason()) && SAFE_KEY.matcher(invalid.key()).matches()) {
            params.put("key", invalid.key());
        }
        return json.error(ErrorCode.PARAM_INVALID, "a param is invalid: " + invalid.reason(), params);
    }

    static ResponseEntity<String> forbidden(WebJson json, PluginGate.ForbiddenParam forbidden) {
        return json.error(ErrorCode.PARAM_FORBIDDEN, forbidden.getMessage(), Map.of("key", forbidden.key()));
    }
}
