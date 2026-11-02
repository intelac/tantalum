package tantalum.shell.auth;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 认证规则：{@code /api} 只有登录后才能访问，未登录返回 401。
 * 不用 CSRF token：Cookie 是 {@code SameSite=Strict}，写操作再校验 {@code Origin}。
 */
@Configuration
public class SecurityConfig {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, UserStore users, SecurityContextRepository contexts) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .securityContext(context -> context.securityContextRepository(contexts))
                .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, failure) -> {
                    response.setStatus(401);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"error\":\"not_authenticated\"}");
                }))
                .authorizeHttpRequests(rules -> rules
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers("/api/auth/login").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .addFilterBefore(new OriginFilter(), AnonymousAuthenticationFilter.class)
                .addFilterBefore(new DisabledUserFilter(users), AnonymousAuthenticationFilter.class);
        return http.build();
    }

    /** 不只绑定本机时，Cookie 必须带 Secure，否则会话会明文经过网络。 */
    /** {@code server.address} 没设时 Spring 绑定全部网卡，不算回环。 */
    public static void requireSecureCookie(String address, boolean secure) {
        if (!isLoopback(address) && !secure) {
            throw new IllegalStateException("server.address is " + address + " but the session cookie is not Secure. "
                    + "Serve over HTTPS and set server.servlet.session.cookie.secure=true.");
        }
    }

    static boolean isLoopback(String address) {
        if (address == null || address.isBlank()) {
            return false;
        }
        if (address.equals("localhost")) {
            return true;
        }
        try {
            return java.net.InetAddress.getByName(address).isLoopbackAddress();
        } catch (java.net.UnknownHostException unknown) {
            return false;
        }
    }

    @Bean
    SecurityContextRepository securityContextRepository(Environment environment) {
        requireSecureCookie(environment.getProperty("server.address"),
                environment.getProperty("server.servlet.session.cookie.secure", Boolean.class, false));
        return new HttpSessionSecurityContextRepository();
    }

    /** 不用用户名密码过滤器，只是让 Spring Boot 不再生成默认用户和默认密码。 */
    @Bean
    UserDetailsService noDefaultUser() {
        return username -> {
            throw new UsernameNotFoundException(username);
        };
    }

    @Bean
    LoginThrottle loginThrottle() {
        return new LoginThrottle(5, 50, Duration.ofMinutes(15), Clock.systemUTC());
    }

    /** 写操作如果带 {@code Origin}，必须和请求的主机一致。 */
    private static final class OriginFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String origin = request.getHeader("Origin");
            if (origin != null && !SAFE_METHODS.contains(request.getMethod()) && !sameHost(origin, request)) {
                response.setStatus(403);
                return;
            }
            chain.doFilter(request, response);
        }

        private static boolean sameHost(String origin, HttpServletRequest request) {
            try {
                URI uri = URI.create(origin);
                return request.getServerName().equalsIgnoreCase(uri.getHost()) && request.getServerPort() == effectivePort(uri);
            } catch (IllegalArgumentException malformed) {
                return false;
            }
        }

        private static int effectivePort(URI uri) {
            if (uri.getPort() != -1) {
                return uri.getPort();
            }
            return "https".equals(uri.getScheme()) ? 443 : 80;
        }
    }

    /** 每个请求都查一次用户是否被停用、登录后是否改过密码，这样命令行停用或改密码后旧的登录立即失效。 */
    private static final class DisabledUserFilter extends OncePerRequestFilter {
        private final LoginCheck check;

        DisabledUserFilter(UserStore users) {
            this.check = new LoginCheck(users);
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.isAuthenticated()) {
                try {
                    if (!check.accountValid(authentication.getName(), request.getSession(false))) {
                        SecurityContextHolder.clearContext();
                        var session = request.getSession(false);
                        if (session != null) {
                            session.invalidate();
                        }
                        response.setStatus(401);
                        return;
                    }
                } catch (SQLException failure) {
                    throw new ServletException(failure);
                }
            }
            chain.doFilter(request, response);
        }
    }
}
