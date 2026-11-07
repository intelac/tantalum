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
 */
public final class AuthBodyLimitFilter extends OncePerRequestFilter {

    public static final int MAX_BODY = 8 * 1024;
    static final Set<String> PATHS = Set.of("/api/auth/login", "/api/auth/password");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (PATHS.contains(request.getRequestURI()) && !"GET".equals(request.getMethod())) {
            long length = request.getContentLengthLong();
            if (length < 0) {
                refuse(response, 411, "Content-Length is required");
                return;
            }
            if (length > MAX_BODY) {
                refuse(response, 413, "the request body is larger than " + MAX_BODY + " bytes");
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
