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
import tantalum.value.json.Json;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 接口的 JSON 收发：所有 Controller 用 journal 的同一个 mapper 写响应、读请求体；当前用户也从这里取。 */
@Component
final class WebJson {

    private final JsonMapper mapper;
    private final Engine engine;
    private volatile ViewRedaction redaction;

    WebJson(Engine engine) {
        this.mapper = engine.codec().mapper();
        this.engine = engine;
    }

    /** 一个值的 JSON 文字（SSE 的数据用）。管理员只读查看时遮掉个人插件的内容。 */
    String write(Object value) {
        return viewing() ? redacted(value) : mapper.writeValueAsString(value);
    }

    ResponseEntity<String> json(int status, Object body) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(write(body));
    }

    /** 带一次性秘密（如连接令牌）的响应：不让浏览器和代理缓存。 */
    ResponseEntity<String> secret(int status, Object body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON)
                .body(write(body));
    }

    /** 这次请求是管理员在只读查看别人。 */
    static boolean viewing() {
        return SecurityContextHolder.getContext().getAuthentication() instanceof tantalum.shell.auth.ViewAs.Viewing;
    }

    /** 已经写好的 JSON 文字（SSE、轮询推给只读查看者的）：遮掉个人插件的内容；不是 JSON 的按文字遮。 */
    String redactText(String data) {
        ViewRedaction rules = redaction();
        if (rules.idle() || data == null) {
            return data;
        }
        try {
            return mapper.writeValueAsString(rules.redact(mapper.readTree(data)));
        } catch (RuntimeException notJson) {
            return rules.redactText(data);
        }
    }

    private String redacted(Object value) {
        ViewRedaction rules = redaction();
        if (rules.idle()) {
            return mapper.writeValueAsString(value);
        }
        return mapper.writeValueAsString(rules.redact(mapper.valueToTree(value)));
    }

    /** 个人插件、它们的工具和段名；第一次用时从引擎算一次（插件在启动时就定了）。 */
    ViewRedaction redaction() {
        ViewRedaction rules = redaction;
        if (rules == null) {
            java.util.Set<String> personal = engine.plugins().personalPlugins();
            java.util.Set<String> tools = new java.util.HashSet<>();
            engine.tools().forEach(tool -> {
                if (tool.plugin() != null && personal.contains(tool.plugin())) {
                    tools.add(tool.name());
                }
            });
            java.util.Set<String> sections = new java.util.HashSet<>();
            engine.members(tantalum.shell.plugin.ContextContributor.class).forEach(contributor -> {
                if (personal.contains(contributor.plugin())) {
                    sections.addAll(contributor.sectionNames());
                }
            });
            rules = new ViewRedaction(personal, tools, sections);
            redaction = rules;
        }
        return rules;
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

    /** 请求里的一个值换成内核的 {@link Json}。 */
    Json value(JsonNode node) {
        return mapper.treeToValue(node, Json.class);
    }

    /** 字段不存在或不是字符串时为 null。 */
    static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isString() ? null : value.asString();
    }

    static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 身份只来自登录会话，不看任何请求头。管理员只读查看别人时（{@code auth.ViewAs}），这里是被看的人：
     * 那一次请求已经被限制成白名单里的 GET，所以读到的是他的数据，改不了任何东西。
     */
    static Principal currentUser() {
        return new Principal(SecurityContextHolder.getContext().getAuthentication().getName());
    }

    /** 真正登录的人：平时和 {@link #currentUser} 一样，只读查看时是那个管理员。查登录还有没有效用它。 */
    static Principal viewer() {
        return SecurityContextHolder.getContext().getAuthentication() instanceof tantalum.shell.auth.ViewAs.Viewing viewing
                ? new Principal(viewing.viewer()) : currentUser();
    }
}
