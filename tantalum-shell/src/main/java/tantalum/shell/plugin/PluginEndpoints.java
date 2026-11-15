package tantalum.shell.plugin;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import tantalum.value.id.Principal;
import tantalum.value.json.Json;

/**
 * 插件自己的接口（memory-plugin 需求 1.5）：{@code /api/plugin-api/<插件>/<路径>}，只给当前登录的用户用。JSON 由平台解析和写出。
 * 插件对这个用户关着时平台回 404；管理员只读查看时只有 GET 进得来（{@code readOnly} 为真），写的平台直接拒绝。
 */
public interface PluginEndpoints {

    String plugin();

    PluginResponse handle(PluginRequest request);

    /** 管理员只读查看别人时，这条路径（插件名后面的部分）能不能看。默认都不能。 */
    default boolean viewable(java.util.List<String> path) {
        return false;
    }

    /**
     * {@code method} 大写；{@code path} 是插件名后面的部分，按 {@code /} 分好、已解码（可能为空表）；{@code body} 是解析好的请求体，
     * 没有时为 null（不是合法 JSON 的请求平台直接回 400，不交给插件）。
     */
    record PluginRequest(String method, java.util.List<String> path, Map<String, String> query, Json body, Principal user,
                         boolean readOnly) {

        public PluginRequest {
            path = java.util.List.copyOf(path);
            query = Map.copyOf(query);
        }
    }

    /** {@code body} 为 null 时没有响应体（如 204）。 */
    record PluginResponse(int status, Json body) {

        public static PluginResponse ok(Json body) {
            return new PluginResponse(200, body);
        }

        public static PluginResponse noContent() {
            return new PluginResponse(204, null);
        }

        /**
         * 和平台的错误同一个样子：{@code {"error": 说明, "code": 代码, "params": {...}}}，控制台按代码显示文字。
         * {@code params} 只放显示用的数字和名字。
         */
        public static PluginResponse error(int status, String code, String diagnostic, Map<String, Json> params) {
            Map<String, Json> body = new LinkedHashMap<>();
            body.put("error", new Json.Str(diagnostic));
            body.put("code", new Json.Str(code));
            if (!params.isEmpty()) {
                body.put("params", new Json.Obj(new TreeMap<>(params)));
            }
            return new PluginResponse(status, new Json.Obj(body));
        }
    }
}
