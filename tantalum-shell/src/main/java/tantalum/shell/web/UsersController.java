package tantalum.shell.web;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tantalum.shell.auth.UserStore;
import tantalum.shell.boot.Engine;
import tantalum.value.id.Principal;
import tools.jackson.databind.JsonNode;

/**
 * 管理员管用户（ops-hardening-2 需求 2.4、2.5）：列出、新建、停用/启用、设/取消管理员、重置密码。只有管理员能到这里
 * （{@code SecurityConfig} 对 {@code /api/users/**} 要 {@code ROLE_ADMIN}，否则 403）。不能停用或取消自己；系统至少留一个启用的管理员。
 * 重置密码或停用后，那个人的全部登录立即失效、推送连接断开。密码不进日志。
 */
@RestController
public class UsersController {

    private final UserStore users;
    private final Engine engine;
    private final WebJson json;

    public UsersController(UserStore users, Engine engine, WebJson json) {
        this.users = users;
        this.engine = engine;
        this.json = json;
    }

    @GetMapping("/api/users")
    ResponseEntity<String> list() throws SQLException {
        return json.json(200, Map.of("users", users.list().stream().map(UsersController::view).toList()));
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
            if (admin != null) {
                users.setAdmin(name, admin);
            }
            if (disabled != null) {
                if (disabled) {
                    users.disable(name);
                    signOut(name);
                } else {
                    users.enable(name);
                }
            }
        } catch (UserStore.Refused refused) {
            return refused(refused);
        }
        return json.json(200, view(account(name)));
    }

    /** 请求体 {@code {"password"}}。重置后那个人的全部登录失效（D3）。 */
    @PutMapping("/api/users/{name}/password")
    ResponseEntity<String> resetPassword(@PathVariable String name, @RequestBody String body) throws SQLException {
        Optional<JsonNode> input = json.read(body);
        String password = input.map(node -> WebJson.text(node, "password")).orElse(null);
        if (!validPassword(password)) {
            return json.error(ErrorCode.REQUEST_INVALID, "body must be {\"password\": \"...\"}");
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

    static List<Map<String, Object>> views(List<UserStore.Account> accounts) {
        return accounts.stream().map(UsersController::view).toList();
    }
}
