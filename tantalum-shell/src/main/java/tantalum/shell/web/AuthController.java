package tantalum.shell.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tantalum.shell.auth.AuthBodyLimitFilter;
import tantalum.shell.auth.LoginContextRepository;
import tantalum.shell.auth.LoginThrottle;
import tantalum.shell.auth.UserStore;
import tantalum.shell.boot.Engine;
import tantalum.value.id.Principal;
import tantalum.value.text.Language;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** 登录、退出和当前用户。失败时不透露是用户名还是密码错了。登录记在用户库里，重启不丢（ops-hardening-2 需求 1）。 */
@RestController
public class AuthController {

    static final int MAX_BODY = AuthBodyLimitFilter.MAX_BODY;
    static final int MAX_USERNAME = 64;
    static final int MAX_PASSWORD = 1024;
    static final int MIN_PASSWORD = UserStore.MIN_PASSWORD;

    private final UserStore users;
    private final LoginThrottle throttle;
    private final LoginContextRepository contexts;
    private final Engine engine;
    private final WebJson json;

    public AuthController(UserStore users, LoginThrottle throttle, LoginContextRepository contexts, Engine engine, WebJson json) {
        this.users = users;
        this.throttle = throttle;
        this.contexts = contexts;
        this.engine = engine;
        this.json = json;
    }

    /** 未认证入口：先限长度再解析，超长输入不进 Argon2，也不占限速表。 */
    @PostMapping("/api/auth/login")
    ResponseEntity<String> login(@RequestBody String body, HttpServletRequest request, HttpServletResponse response)
            throws SQLException {
        if (body.length() > MAX_BODY) {
            return json.error(ErrorCode.REQUEST_INVALID, "invalid_request");
        }
        Optional<JsonNode> input = json.read(body);
        if (input.isEmpty()) {
            return json.error(ErrorCode.REQUEST_INVALID, "invalid_request");
        }
        String username = WebJson.text(input.get(), "username");
        String password = WebJson.text(input.get(), "password");
        if (username == null || password == null || username.isEmpty() || username.length() > MAX_USERNAME
                || password.length() > MAX_PASSWORD) {
            return json.error(ErrorCode.REQUEST_INVALID, "invalid_request");
        }
        String source = request.getRemoteAddr();
        if (!throttle.tryBegin(username, source)) {
            return json.error(ErrorCode.AUTH_TOO_MANY_ATTEMPTS, "too_many_attempts");
        }
        if (!users.verify(username, password)) {
            return json.error(ErrorCode.AUTH_INVALID_CREDENTIALS, "invalid_credentials");
        }
        throttle.succeeded(username, source);
        // 登录前的登录作废（同一浏览器换人登录）：删掉它、断开它的推送连接；同一用户在别处的登录不受影响
        Authentication before = SecurityContextHolder.getContext().getAuthentication();
        String previous = LoginContextRepository.currentLogin(request);
        if (before != null && before.isAuthenticated() && !(before instanceof AnonymousAuthenticationToken) && previous != null) {
            engine.hub().closeLogin(new Principal(before.getName()), previous);
            users.logins().delete(previous);
        }
        contexts.issue(request, username);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(contexts.authentication(username));
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        return json.json(200, user(new Principal(username)));
    }

    @PostMapping("/api/auth/logout")
    ResponseEntity<String> logout(HttpServletRequest request, HttpServletResponse response) throws SQLException {
        String login = LoginContextRepository.currentLogin(request);
        if (login != null) {
            engine.hub().closeLogin(WebJson.currentUser(), login);
            users.logins().delete(login);
        }
        SecurityContextHolder.clearContext();
        contexts.saveContext(SecurityContextHolder.createEmptyContext(), request, response);
        return json.json(200, Map.of("loggedOut", true));
    }

    /**
     * 自己改密码（ops-hardening-2 需求 2.6）：要当前密码；改后别处的登录失效、推送连接断开，当前这个保留（D3）。当前密码错计入登录限速。
     * 请求体 {@code {"current", "next"}}，新密码至少 {@value UserStore#MIN_PASSWORD} 位（review-fixes 需求 11.13）。
     */
    @PutMapping("/api/auth/password")
    ResponseEntity<String> changePassword(@RequestBody String body, HttpServletRequest request) throws SQLException {
        if (body.length() > MAX_BODY) {
            return json.error(ErrorCode.REQUEST_INVALID, "invalid_request");
        }
        Optional<JsonNode> input = json.read(body);
        String current = input.map(node -> WebJson.text(node, "current")).orElse(null);
        String next = input.map(node -> WebJson.text(node, "next")).orElse(null);
        if (current == null || next == null || next.isEmpty() || next.length() > MAX_PASSWORD || current.length() > MAX_PASSWORD) {
            return json.error(ErrorCode.REQUEST_INVALID, "body must be {\"current\": \"...\", \"next\": \"...\"}");
        }
        if (next.length() < MIN_PASSWORD) {
            return json.error(ErrorCode.AUTH_PASSWORD_TOO_SHORT, "next must be at least " + MIN_PASSWORD + " characters");
        }
        Principal user = WebJson.currentUser();
        String name = user.value();
        String source = request.getRemoteAddr();
        if (!throttle.tryBegin(name, source)) {
            return json.error(ErrorCode.AUTH_TOO_MANY_ATTEMPTS, "too_many_attempts");
        }
        if (!users.verify(name, current)) {
            return json.error(ErrorCode.AUTH_INVALID_CREDENTIALS, "invalid_credentials");
        }
        throttle.succeeded(name, source);
        for (String login : users.changePassword(name, next, LoginContextRepository.currentLogin(request))) {
            engine.hub().closeLogin(user, login);
        }
        return json.json(200, Map.of("changed", true));
    }

    @GetMapping("/api/auth/me")
    ResponseEntity<String> me() throws SQLException {
        return json.json(200, user(WebJson.currentUser()));
    }

    /**
     * {@code {"username", "language", "admin"}}：控制台登录后、刷新后立即用这个用户的语言，{@code null} 是跟随浏览器（console-i18n I3）；
     * {@code admin} 决定设置页有没有“用户”一页（ops-hardening-2 需求 2.3）。
     */
    private ObjectNode user(Principal user) throws SQLException {
        ObjectNode view = json.object();
        view.put("username", user.value());
        view.put("language", engine.userSettings().language(user).map(Language::id).orElse(null));
        view.put("admin", users.status(user.value()).map(UserStore.Status::admin).orElse(false));
        return view;
    }
}
