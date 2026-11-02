package tantalum.shell.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import tantalum.shell.auth.LoginCheck;
import tantalum.shell.auth.LoginThrottle;
import tantalum.shell.auth.UserStore;
import tantalum.shell.boot.Engine;
import tantalum.value.id.Principal;
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
    private final WebJson json;

    public AuthController(UserStore users, LoginThrottle throttle, SecurityContextRepository contexts, Engine engine, WebJson json) {
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
            return json.error(400, "invalid_request");
        }
        Optional<JsonNode> input = json.read(body);
        if (input.isEmpty()) {
            return json.error(400, "invalid_request");
        }
        String username = WebJson.text(input.get(), "username");
        String password = WebJson.text(input.get(), "password");
        if (username == null || password == null || username.isEmpty() || username.length() > MAX_USERNAME
                || password.length() > MAX_PASSWORD) {
            return json.error(400, "invalid_request");
        }
        String source = request.getRemoteAddr();
        if (!throttle.tryBegin(username, source)) {
            return json.error(429, "too_many_attempts");
        }
        if (!users.verify(username, password)) {
            return json.error(401, "invalid_credentials");
        }
        throttle.succeeded(username, source);

        // 登录前的会话作废，防止会话固定攻击。它若已登录，只断开这次登录的推送连接，同一用户在别处的登录不受影响。
        Authentication before = SecurityContextHolder.getContext().getAuthentication();
        HttpSession previous = request.getSession(false);
        if (before != null && before.isAuthenticated() && !(before instanceof AnonymousAuthenticationToken) && previous != null) {
            engine.hub().closeLogin(new Principal(before.getName()), previous.getId());
        }
        if (previous != null) {
            previous.invalidate();
        }
        new LoginCheck(users).remember(username, request.getSession(true));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(username, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        return json.json(200, Map.of("username", username));
    }

    @PostMapping("/api/auth/logout")
    ResponseEntity<String> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            engine.hub().closeLogin(WebJson.currentUser(), session.getId());
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return json.json(200, Map.of("loggedOut", true));
    }

    @GetMapping("/api/auth/me")
    ResponseEntity<String> me() {
        return json.json(200, Map.of("username", WebJson.currentUser().value()));
    }
}
