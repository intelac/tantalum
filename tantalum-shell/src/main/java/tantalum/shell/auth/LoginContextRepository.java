package tantalum.shell.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpRequestResponseHolder;
import org.springframework.security.web.context.SecurityContextRepository;

/**
 * 登录放在 {@code users.db} 的登录表里，不用 Tomcat 的内存会话（ops-hardening-2 需求 1）。Cookie {@value #COOKIE} 里只有令牌；
 * 每个请求查一次表：登录在、没过期（最后访问距今不超过 {@code timeout}）、账号没停用、口令戳没变，才算登录了。
 * 无效的令牌一律当作没登录，不区分原因，也不动 Cookie（退出时才清）。最后访问时间最多每分钟写一次。
 */
public final class LoginContextRepository implements SecurityContextRepository {

    public static final String COOKIE = "tantalum.login";
    /** 请求属性：这次请求的登录 id（SSE 按它断开）。 */
    static final String LOGIN_ID = "tantalum.login.id";
    /** 请求属性：登录成功时新发的令牌，{@link #saveContext} 写进 Cookie。 */
    static final String ISSUED_TOKEN = "tantalum.login.issued";
    static final Duration TOUCH_EVERY = Duration.ofMinutes(1);
    private static final Logger LOG = LoggerFactory.getLogger(LoginContextRepository.class);

    private final UserStore users;
    private final Duration timeout;
    private final boolean secure;
    private final Clock clock;

    public LoginContextRepository(UserStore users, Duration timeout, boolean secure, Clock clock) {
        this.users = users;
        this.timeout = timeout;
        this.secure = secure;
        this.clock = clock;
        try {
            int swept = users.logins().sweep(clock.millis(), timeout);
            if (swept > 0) {
                LOG.info("removed {} expired login(s)", swept);
            }
        } catch (SQLException failure) {
            LOG.warn("cannot sweep expired logins: {}", failure.getMessage());
        }
    }

    public Duration timeout() {
        return timeout;
    }

    /** 这次请求的登录 id，没登录为 null。 */
    public static String currentLogin(HttpServletRequest request) {
        return request.getAttribute(LOGIN_ID) instanceof String id ? id : null;
    }

    @Override
    public SecurityContext loadContext(HttpRequestResponseHolder holder) {
        HttpServletRequest request = holder.getRequest();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        String token = tokenOf(request);
        if (token == null) {
            return context;
        }
        try {
            Optional<Logins.Login> login = valid(token, clock.millis());
            if (login.isEmpty()) {
                // 不清 Cookie：带旧令牌的请求可能和刚登录的新令牌并行到达，清掉会把新登录一起清掉；只在退出时清
                return context;
            }
            request.setAttribute(LOGIN_ID, login.get().id());
            context.setAuthentication(authentication(login.get().name()));
        } catch (SQLException failure) {
            // 用户库读不了：这次请求当作没登录，不发令牌、不清 Cookie
            LOG.warn("cannot read the login table: {}", failure.getMessage());
        }
        return context;
    }

    /** 令牌对应的有效登录；顺手刷新最后访问时间。 */
    private Optional<Logins.Login> valid(String token, long now) throws SQLException {
        Optional<Logins.Login> login = users.logins().check(token, now, timeout);
        if (login.isEmpty() || !accountValid(login.get())) {
            return Optional.empty();
        }
        if (now - login.get().lastSeen() >= TOUCH_EVERY.toMillis()) {
            users.logins().touch(login.get().id(), now);
        }
        return login;
    }

    /** 账号没停用、口令戳没变。 */
    private boolean accountValid(Logins.Login login) throws SQLException {
        Optional<UserStore.Status> status = users.status(login.name());
        return status.isPresent() && !status.get().disabled() && status.get().stamp() == login.stamp();
    }

    /** 长连接用：按登录 id 查，每个心跳一次。 */
    public boolean stillValid(String name, String loginId, long now) throws SQLException {
        if (loginId == null) {
            return false;
        }
        Optional<Logins.Login> login = users.logins().byId(loginId, now, timeout);
        return login.isPresent() && login.get().name().equals(name) && accountValid(login.get());
    }

    /** 登录成功：发一条新登录，令牌经 {@link #saveContext} 写进 Cookie。顺手清掉过期的登录，表不随历史增长（需求 1.5）。 */
    public Logins.Issued issue(HttpServletRequest request, String name) throws SQLException {
        long now = clock.millis();
        users.logins().sweep(now, timeout);
        long stamp = users.status(name).map(UserStore.Status::stamp).orElse(-1L);
        Logins.Issued issued = users.logins().create(name, stamp, now);
        request.setAttribute(ISSUED_TOKEN, issued.token());
        request.setAttribute(LOGIN_ID, issued.id());
        return issued;
    }

    /** 用户、角色。 */
    public Authentication authentication(String name) throws SQLException {
        boolean admin = users.status(name).map(UserStore.Status::admin).orElse(false);
        List<GrantedAuthority> authorities = admin
                ? List.of(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_ADMIN"))
                : List.of(new SimpleGrantedAuthority("ROLE_USER"));
        return new UsernamePasswordAuthenticationToken(name, null, authorities);
    }

    @Override
    public void saveContext(SecurityContext context, HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication = context.getAuthentication();
        boolean loggedIn = authentication != null && authentication.isAuthenticated() && !(authentication instanceof AnonymousAuthenticationToken);
        if (loggedIn && request.getAttribute(ISSUED_TOKEN) instanceof String token) {
            response.addHeader(HttpHeaders.SET_COOKIE, cookie(token).toString());
            request.removeAttribute(ISSUED_TOKEN);
        } else if (!loggedIn && tokenOf(request) != null) {
            response.addHeader(HttpHeaders.SET_COOKIE, expired().toString());
        }
    }

    @Override
    public boolean containsContext(HttpServletRequest request) {
        return tokenOf(request) != null;
    }

    private static String tokenOf(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (COOKIE.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isEmpty()) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private ResponseCookie cookie(String token) {
        return ResponseCookie.from(COOKIE, token).httpOnly(true).secure(secure).sameSite("Strict").path("/").maxAge(timeout).build();
    }

    private ResponseCookie expired() {
        return ResponseCookie.from(COOKIE, "").httpOnly(true).secure(secure).sameSite("Strict").path("/").maxAge(0).build();
    }
}
