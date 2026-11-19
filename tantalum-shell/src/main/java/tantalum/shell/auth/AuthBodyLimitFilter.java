package tantalum.shell.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 登录和改密码的请求体先看 {@code Content-Length} 再读：超过 {@value #MAX_BODY} 字节直接 413，
 * 没有长度（分块传输）411，都不读体、不解析。这两个入口一个不用登录、一个进 Argon2，不能让人先把体送进来。
 * <p>插件的接口（{@code /api/plugin-api/…}）的写请求同样先看长度，上限 {@value #MAX_PLUGIN_BODY} 字节（review 2026-11-17：
 * 那里整个请求体读成一个字符串再解析，没有上限时登录的人能用一个很大的请求占住内存）。
 */
public final class AuthBodyLimitFilter extends OncePerRequestFilter {

    public static final int MAX_BODY = 8 * 1024;
    static final Set<String> PATHS = Set.of("/api/auth/login", "/api/auth/password");
    /** 插件接口一次写请求的上限：记忆条目、参数这类内容远小于它。 */
    public static final int MAX_PLUGIN_BODY = 1024 * 1024;
    static final String PLUGIN_PREFIX = "/api/plugin-api/";
    private static final Set<String> WRITES = Set.of("POST", "PUT", "PATCH");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean auth = PATHS.contains(request.getRequestURI()) && !"GET".equals(request.getMethod());
        boolean plugin = request.getRequestURI().startsWith(PLUGIN_PREFIX) && WRITES.contains(request.getMethod());
        if (auth || plugin) {
            int max = auth ? MAX_BODY : MAX_PLUGIN_BODY;
            long length = request.getContentLengthLong();
            if (length < 0) {
                refuse(response, 411, "Content-Length is required");
                return;
            }
            if (length > max) {
                refuse(response, 413, "the request body is larger than " + max + " bytes");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private static void refuse(HttpServletResponse response, int status, String reason) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        // 代码同 web.ErrorCode.REQUEST_INVALID（auth 不依赖 web，这里写字面值）
        response.getWriter().write("{\"error\":\"" + reason + "\",\"code\":\"request/invalid\"}");
    }
}
