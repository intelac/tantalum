package tantalum.shell.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tantalum.shell.auth.LoginThrottle;
import tantalum.shell.auth.UserStore;
import tantalum.shell.boot.Engine;
import tantalum.value.id.Principal;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/** 登录、退出和当前用户。失败时不透露是用户名还是密码错了。 */
@RestController
public class AuthController {

    static final int MAX_BODY = 8 * 1024;
    static final int MAX_USERNAME = 64;
    static final int MAX_PASSWORD = 1024;

    private final UserStore users;
    private final LoginThrottle throttle;
    private final SecurityContextRepository contexts;
    private final Engine engine;

    public AuthController(UserStore users, LoginThrottle throttle, SecurityContextRepository contexts, Engine engine) {
        this.users = users;
        this.throttle = throttle;
        this.contexts = contexts;
        this.engine = engine;
    }

    /** 未认证入口：先限长度再解析，超长输入不进 Argon2，也不占限速表。 */
    @PostMapping("/api/auth/login")
    ResponseEntity<String> login(@RequestBody String body, HttpServletRequest request, HttpServletResponse response)
            throws SQLException {
        if (body.length() > MAX_BODY) {
            return json(400, Map.of("error", "invalid_request"));
        }
        JsonNode input;
        try {
            input = engine.codec().mapper().readTree(body);
        } catch (JacksonException failure) {
            return json(400, Map.of("error", "invalid_request"));
        }
        String username = text(input, "username");
        String password = text(input, "password");
        if (username == null || password == null || username.isEmpty() || username.length() > MAX_USERNAME
                || password.length() > MAX_PASSWORD) {
            return json(400, Map.of("error", "invalid_request"));
        }
        String source = request.getRemoteAddr();
        if (!throttle.tryBegin(username, source)) {
            return json(429, Map.of("error", "too_many_attempts"));
        }
        if (!users.verify(username, password)) {
            return json(401, Map.of("error", "invalid_credentials"));
        }
        throttle.succeeded(username, source);

        // 登录前的会话作废，防止会话固定攻击；它若已登录，断开那个用户的推送连接。
        Authentication before = SecurityContextHolder.getContext().getAuthentication();
        if (before != null && before.isAuthenticated() && !(before instanceof AnonymousAuthenticationToken)) {
            engine.hub().closeAll(new Principal(before.getName()));
        }
        HttpSession previous = request.getSession(false);
        if (previous != null) {
            previous.invalidate();
        }
        request.getSession(true);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(username, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        return json(200, Map.of("username", username));
    }

    @PostMapping("/api/auth/logout")
    ResponseEntity<String> logout(HttpServletRequest request) {
        engine.hub().closeAll(new Principal(SecurityContextHolder.getContext().getAuthentication().getName()));
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return json(200, Map.of("loggedOut", true));
    }

    @GetMapping("/api/auth/me")
    ResponseEntity<String> me() {
        return json(200, Map.of("username", SecurityContextHolder.getContext().getAuthentication().getName()));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isString() ? null : value.asString();
    }

    private ResponseEntity<String> json(int status, Object body) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(engine.codec().mapper().writeValueAsString(body));
    }
}
