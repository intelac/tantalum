package tantalum.shell.web;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tantalum.shell.auth.UserStore;
import tantalum.shell.access.PluginGate;
import tantalum.shell.boot.Engine;
import tantalum.shell.conversation.SessionSummary;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;
import tantalum.value.model.TokenUsage;
import tools.jackson.databind.JsonNode;

/**
 * 管理员管用户：列出、新建、停用/启用、设/取消管理员、重置密码。只有管理员能到这里
 * （{@code SecurityConfig} 对 {@code /api/users/**} 要 {@code ROLE_ADMIN}，否则 403）。不能停用或取消自己；系统至少留一个启用的管理员。
 * 重置密码或停用后，那个人的全部登录立即失效、推送连接断开。密码不进日志。
 */
@RestController
public class UsersController {

    private final UserStore users;
    private final Engine engine;
    private final WebJson json;
    private final tantalum.shell.auth.ViewAs viewAs;

    public UsersController(UserStore users, Engine engine, WebJson json, tantalum.shell.auth.ViewAs viewAs) {
        this.viewAs = viewAs;
        this.users = users;
        this.engine = engine;
        this.json = json;
    }

    /**
     * 运维表格（qagent-align 需求 4、5）。每人：今天（UTC）的用量、没归档的会话数、此刻在跑或在等回答的运行数、每日上限
     * （{@code {tokens?, own}}，他没开报上限的插件时是 {@code {off: true}}；没有任何插件报上限时没有这一项）。
     * 顶层：{@code dailyLimit} 是表格里能直接改的那个上限（哪个插件的哪个参数、所有人的默认、范围），没有插件说明是哪个参数时没有；
     * {@code weighted}、{@code weights} 是折算系数（只列不全是 1 的）；{@code viewAs} 是只读查看别人的会话这个功能开没开。平台不认识具体插件，这些都从插件点来。
     */
    @GetMapping("/api/users")
    ResponseEntity<String> list() throws SQLException {
        LocalDate today = LocalDate.ofInstant(engine.clock().instant(), ZoneOffset.UTC);
        List<Map<String, Object>> views = new ArrayList<>();
        for (UserStore.Account account : users.list()) {
            Principal user = new Principal(account.name());
            Map<String, Object> view = view(account);
            view.put("usageToday", usage(engine.queries().usageOn(user, today)));
            Set<SessionId> archived = engine.marks().allArchived(user);
            List<SessionSummary> sessions = engine.queries().sessions(user).stream().filter(session -> !archived.contains(session.id())).toList();
            view.put("sessions", sessions.size());
            view.put("running", sessions.stream().filter(session -> !SessionSummary.IDLE.equals(session.runStatus())).count());
            PluginGate.UserLimit limit = engine.plugins().limitOf(user);
            if (limit.reported()) {
                Map<String, Object> shown = new LinkedHashMap<>();
                if (!limit.enabled()) {
                    shown.put("off", true);
                } else {
                    limit.tokens().ifPresent(tokens -> shown.put("tokens", tokens));
                    shown.put("own", limit.own());
                }
                view.put("limit", shown);
            }
            views.add(view);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("users", views);
        engine.plugins().limitSource().ifPresent(source -> {
            Map<String, Object> shown = new LinkedHashMap<>();
            shown.put("plugin", source.plugin());
            shown.put("key", source.param().key());
            shown.put("value", source.value());
            if (source.param().min() != null) {
                shown.put("min", source.param().min());
            }
            if (source.param().max() != null) {
                shown.put("max", source.param().max());
            }
            response.put("dailyLimit", shown);
        });
        // 管理员能不能只读查看别人的会话（部署的人可以关掉）：页面据此显示按钮
        response.put("viewAs", viewAs.enabled());
        response.put("weighted", engine.weights().any());
        response.put("weights", engine.weights().rows().stream().map(row -> {
            Map<String, Object> shown = new LinkedHashMap<>();
            shown.put("route", row.route());
            if (row.model() != null) {
                shown.put("model", row.model());
            }
            shown.put("input", row.weights().input());
            shown.put("output", row.weights().output());
            shown.put("cacheRead", row.weights().cacheRead());
            shown.put("cacheWrite", row.weights().cacheWrite());
            return shown;
        }).toList());
        return json.json(200, response);
    }

    /** 请求体 {@code {"name", "password", "admin"}}。 */
    @PostMapping("/api/users")
    ResponseEntity<String> create(@RequestBody String body) throws SQLException {
        Optional<JsonNode> input = json.read(body);
        String name = input.map(node -> WebJson.text(node, "name")).orElse(null);
        String password = input.map(node -> WebJson.text(node, "password")).orElse(null);
        boolean admin = input.map(node -> node.path("admin").asBoolean(false)).orElse(false);
        if (WebJson.blank(name) || name.length() > AuthController.MAX_USERNAME || !validPassword(password)) {
            return json.error(ErrorCode.REQUEST_INVALID, "body must be {\"name\": \"...\", \"password\": \"...\", \"admin\": true|false}");
        }
        if (password.length() < AuthController.MIN_PASSWORD) {
            return tooShort();
        }
        try {
            users.add(name, password, admin);
        } catch (UserStore.Refused refused) {
            return refused(refused);
        }
        return json.json(200, view(account(name)));
    }

    /** 请求体 {@code {"disabled"?: bool, "admin"?: bool}}，给了哪个改哪个。 */
    @PutMapping("/api/users/{name}")
    ResponseEntity<String> update(@PathVariable String name, @RequestBody String body) throws SQLException {
        Optional<JsonNode> input = json.read(body);
        if (input.isEmpty()) {
            return json.error(ErrorCode.REQUEST_INVALID, "body must be {\"disabled\"?: bool, \"admin\"?: bool}");
        }
        JsonNode node = input.get();
        Boolean disabled = node.has("disabled") && node.get("disabled").isBoolean() ? node.get("disabled").asBoolean() : null;
        Boolean admin = node.has("admin") && node.get("admin").isBoolean() ? node.get("admin").asBoolean() : null;
        if (disabled == null && admin == null) {
            return json.error(ErrorCode.REQUEST_INVALID, "nothing to change");
        }
        boolean self = name.equals(WebJson.currentUser().value());
        if (self && (Boolean.TRUE.equals(disabled) || Boolean.FALSE.equals(admin))) {
            return json.error(ErrorCode.USER_SELF, "you cannot disable or demote your own account");
        }
        if (users.status(name).isEmpty()) {
            return json.error(ErrorCode.USER_NOT_FOUND, "no such user");
        }
        try {
            users.change(name, admin, disabled);
        } catch (UserStore.Refused refused) {
            return refused(refused);
        }
        if (Boolean.TRUE.equals(disabled)) {
            signOut(name);
        }
        return json.json(200, view(account(name)));
    }

    /** 请求体 {@code {"password"}}，至少 {@value UserStore#MIN_PASSWORD} 位。重置后那个人的全部登录失效。 */
    @PutMapping("/api/users/{name}/password")
    ResponseEntity<String> resetPassword(@PathVariable String name, @RequestBody String body) throws SQLException {
        Optional<JsonNode> input = json.read(body);
        String password = input.map(node -> WebJson.text(node, "password")).orElse(null);
        if (!validPassword(password)) {
            return json.error(ErrorCode.REQUEST_INVALID, "body must be {\"password\": \"...\"}");
        }
        if (password.length() < AuthController.MIN_PASSWORD) {
            return tooShort();
        }
        if (users.status(name).isEmpty()) {
            return json.error(ErrorCode.USER_NOT_FOUND, "no such user");
        }
        users.resetPassword(name, password);
        signOut(name);
        return json.json(200, Map.of("name", name, "reset", true));
    }

    /** 这个人的全部登录删掉、推送连接断开。 */
    private void signOut(String name) throws SQLException {
        users.logins().deleteAll(name);
        engine.hub().closeAll(new Principal(name));
    }

    private static boolean validPassword(String password) {
        return password != null && !password.isEmpty() && password.length() <= AuthController.MAX_PASSWORD;
    }

    private ResponseEntity<String> tooShort() {
        return json.error(ErrorCode.AUTH_PASSWORD_TOO_SHORT, "password must be at least " + AuthController.MIN_PASSWORD + " characters");
    }

    private UserStore.Account account(String name) throws SQLException {
        return users.list().stream().filter(account -> account.name().equals(name)).findFirst().orElseThrow();
    }

    private ResponseEntity<String> refused(UserStore.Refused refused) {
        return switch (refused.kind()) {
            case BAD_NAME -> json.error(ErrorCode.USER_BAD_NAME, refused.getMessage());
            case RESERVED_NAME -> json.error(ErrorCode.USER_RESERVED_NAME, refused.getMessage());
            case EXISTS -> json.error(ErrorCode.USER_EXISTS, refused.getMessage());
            case NO_SUCH -> json.error(ErrorCode.USER_NOT_FOUND, refused.getMessage());
            case LAST_ADMIN -> json.error(ErrorCode.USER_LAST_ADMIN, refused.getMessage());
        };
    }

    static Map<String, Object> view(UserStore.Account account) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("name", account.name());
        view.put("admin", account.admin());
        view.put("disabled", account.disabled());
        view.put("createdAt", account.createdAt().toString());
        return view;
    }

    /** {@code total} 和每日额度插件算的是同一个数（{@code TokenUsage.counted()}）：配了折算系数就是折算后的，否则是四项之和。 */
    private static Map<String, Object> usage(TokenUsage usage) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("input", usage.input());
        view.put("output", usage.output());
        view.put("cacheRead", usage.cacheRead());
        view.put("cacheWrite", usage.cacheWrite());
        view.put("total", usage.counted());
        return view;
    }

}
