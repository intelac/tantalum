package tantalum.shell.web;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import tantalum.shell.boot.Engine;
import tantalum.value.id.Principal;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 接口的 JSON 收发：所有 Controller 用 journal 的同一个 mapper 写响应、读请求体；当前用户也从这里取。 */
@Component
final class WebJson {

    private final JsonMapper mapper;

    WebJson(Engine engine) {
        this.mapper = engine.codec().mapper();
    }

    ResponseEntity<String> json(int status, Object body) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(mapper.writeValueAsString(body));
    }

    /** 带一次性秘密（如连接令牌）的响应：不让浏览器和代理缓存。 */
    ResponseEntity<String> secret(int status, Object body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON)
                .body(mapper.writeValueAsString(body));
    }

    /** 错误：{@code {"error": 英文诊断, "code": 代码, "params": {…}}}，没有参数时不写 {@code params}。 */
    ResponseEntity<String> error(ErrorCode code, String diagnostic) {
        return error(code.status(), code, diagnostic, Map.of());
    }

    ResponseEntity<String> error(ErrorCode code, String diagnostic, Map<String, ?> params) {
        return error(code.status(), code, diagnostic, params);
    }

    /** 同一个代码状态码会变的（会话不可用、提交被拒）用这个；状态码照旧，只加代码。 */
    ResponseEntity<String> error(int status, ErrorCode code, String diagnostic) {
        return error(status, code, diagnostic, Map.of());
    }

    /** {@code params} 只放显示用的数字和名字，不放路径、令牌、用户写的内容。 */
    ResponseEntity<String> error(int status, ErrorCode code, String diagnostic, Map<String, ?> params) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", diagnostic);
        body.put("code", code.wire());
        if (!params.isEmpty()) {
            // 按键排序写出，同一个错误每次都一样
            body.put("params", new TreeMap<>(params));
        }
        return json(status, body);
    }

    /** 要写出显式 {@code null} 的响应用它拼：journal 的 mapper 省略值为 null 的字段，树节点里的 null 照样写出。 */
    tools.jackson.databind.node.ObjectNode object() {
        return mapper.createObjectNode();
    }

    JsonNode tree(Object value) {
        return mapper.valueToTree(value);
    }

    /** 请求体不是合法 JSON 时为空。 */
    Optional<JsonNode> read(String body) {
        try {
            return Optional.of(mapper.readTree(body));
        } catch (JacksonException failure) {
            return Optional.empty();
        }
    }

    /** 字段不存在或不是字符串时为 null。 */
    static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isString() ? null : value.asString();
    }

    static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /** 身份只来自登录会话，不看任何请求头。 */
    static Principal currentUser() {
        return new Principal(SecurityContextHolder.getContext().getAuthentication().getName());
    }
}
