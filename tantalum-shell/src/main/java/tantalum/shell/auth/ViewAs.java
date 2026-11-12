package tantalum.shell.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 管理员只读查看别人的会话（qagent-align 需求 6）。请求带 {@code viewAs=用户名} 时：必须是管理员、功能开着、是 GET、
 * 路径在下面的白名单里，才把这一次请求的身份换成被看的人；控制器照常用“当前用户”取数据，一行不用改。别的一律拒绝——
 * 写操作、白名单以外的读（记忆、插件和参数、本机工作区、设置……）都在这里挡住，不靠页面藏按钮。
 *
 * <p>换上去的身份只有普通用户的角色，没有管理员的：就算白名单写错，管理接口也进不去。换的是这一次请求的上下文，
 * 不保存，登录本身不变。每次开始查看记一行日志（谁、看谁），不记路径和内容。
 */
public final class ViewAs extends OncePerRequestFilter {

    /** 查询参数的名字。用参数不用请求头，是因为图片和下载链接带不了头，一种办法管所有请求。 */
    public static final String PARAM = "viewAs";

    private static final Logger LOG = LoggerFactory.getLogger(ViewAs.class);
    /** 同一次登录看同一个人，隔这么久没有请求再来才算新的一次查看、再记一行。 */
    static final Duration LOG_EVERY = Duration.ofMinutes(10);
    private static final int REMEMBERED = 1000;

    /** 只读查看能读的接口，只有这些。新加的接口默认不在里面。 */
    private static final List<Pattern> READABLE = List.of(
            Pattern.compile("/api/sessions"),
            Pattern.compile("/api/sessions/[^/]+/(messages|runs|trace|context|questions)"),
            Pattern.compile("/api/sessions/[^/]+/trace/calls/[^/]+"),
            Pattern.compile("/api/runs/[^/]+"),
            Pattern.compile("/api/runs/[^/]+/turns"),
            Pattern.compile("/api/runs/[^/]+/turns/[^/]+"),
            Pattern.compile("/api/attachments/[^/]+"),
            Pattern.compile("/api/projects"),
            Pattern.compile("/api/events"),
            Pattern.compile("/api/events/poll"));

    /** 查看时的身份：名字是被看的人，只有普通用户的角色；{@link #viewer} 是真正登录的管理员。 */
    public static final class Viewing extends AbstractAuthenticationToken {
        private final String viewed;
        private final String viewer;

        Viewing(String viewed, String viewer) {
            super(List.of(new SimpleGrantedAuthority("ROLE_USER")));
            this.viewed = viewed;
            this.viewer = viewer;
            setAuthenticated(true);
        }

        @Override public Object getCredentials() { return null; }
        @Override public Object getPrincipal() { return viewed; }
        @Override public String getName() { return viewed; }

        public String viewer() {
            return viewer;
        }
    }

    private final UserStore users;
    private final boolean enabled;
    private final Clock clock;
    private final Map<String, Long> seen = new ConcurrentHashMap<>();

    public ViewAs(UserStore users, boolean enabled, Clock clock) {
        this.users = users;
        this.enabled = enabled;
        this.clock = clock;
    }

    /** 部署的人没关掉这个功能（{@code tantalum.view-as.enabled}，默认开）。 */
    public boolean enabled() {
        return enabled;
    }

    static boolean readable(String path) {
        return READABLE.stream().anyMatch(pattern -> pattern.matcher(path).matches());
    }

    /** 长连接每个心跳问一次：这个人现在还能不能查看别人（还是管理员、没停用、功能开着）。 */
    public boolean stillAllowed(String viewer) throws SQLException {
        return enabled && users.status(viewer).map(status -> status.admin() && !status.disabled()).orElse(false);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String target = request.getParameter(PARAM);
        SecurityContext before = SecurityContextHolder.getContext();
        Authentication real = before.getAuthentication();
        if (target == null || real == null || !real.isAuthenticated() || real instanceof AnonymousAuthenticationToken) {
            // 没带参数：照常。没登录：交给后面的规则回 401
            chain.doFilter(request, response);
            return;
        }
        boolean admin = real.getAuthorities().stream().anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
        if (!enabled || !admin) {
            // 代码同 web.ErrorCode.VIEW_FORBIDDEN（auth 不依赖 web，这里写字面值；ErrorCodeTest 比对）
            refuse(response, 403, "{\"error\":\"view_forbidden\",\"code\":\"view/forbidden\"}");
            return;
        }
        if (!"GET".equals(request.getMethod()) || !readable(request.getRequestURI())) {
            // 代码同 web.ErrorCode.VIEW_READ_ONLY
            refuse(response, 403, "{\"error\":\"view_read_only\",\"code\":\"view/read-only\"}");
            return;
        }
        boolean exists;
        try {
            exists = users.status(target).isPresent();
        } catch (SQLException failure) {
            throw new ServletException(failure);
        }
        if (!exists) {
            // 代码同 web.ErrorCode.USER_NOT_FOUND
            refuse(response, 404, "{\"error\":\"no such user\",\"code\":\"user/not-found\"}");
            return;
        }
        started(real.getName(), LoginContextRepository.currentLogin(request), target);
        SecurityContext viewing = SecurityContextHolder.createEmptyContext();
        viewing.setAuthentication(new Viewing(target, real.getName()));
        SecurityContextHolder.setContext(viewing);
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.setContext(before);
        }
    }

    private static void refuse(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write(body);
    }

    /** 一次查看的开头记一行：谁、看谁。之后同一次登录接着看同一个人不再记，隔了 {@link #LOG_EVERY} 才算新的一次。 */
    private void started(String viewer, String login, String target) {
        long now = clock.millis();
        String key = login + "\n" + target;
        Long last = seen.put(key, now);
        if (last == null || now - last >= LOG_EVERY.toMillis()) {
            LOG.info("admin {} is viewing {}'s sessions (read-only)", viewer, target);
        }
        if (seen.size() > REMEMBERED) {
            seen.values().removeIf(at -> now - at >= LOG_EVERY.toMillis());
        }
    }
}
