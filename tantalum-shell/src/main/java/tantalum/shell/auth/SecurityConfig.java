package tantalum.shell.auth;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import tantalum.shell.net.Hosts;

/**
 * 认证规则：{@code /api} 只有登录后才能访问，未登录返回 401；{@code /api/users} 只有管理员，不是管理员返回 403。
 * 登录在 {@code users.db} 的登录表里（{@link LoginContextRepository}），Cookie 里只有令牌，重启不丢。
 * 不用 CSRF token：Cookie 是 {@code SameSite=Strict}，写操作再校验 {@code Origin}。
 * 例外是连接程序的握手 {@code /api/local/connect}，它带令牌，不带登录会话。
 */
@Configuration
public class SecurityConfig {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    /** 登录过期时间：沿用 Spring 的会话超时配置项（默认 12 小时）。 */
    static final String TIMEOUT_PROPERTY = "server.servlet.session.timeout";

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, LoginContextRepository contexts, ViewAs viewAs) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .securityContext(context -> context.securityContextRepository(contexts))
                // 不存“登录前被拒的请求”：没有跳转登录页的流程用得上它（Playwright 实测）
                .requestCache(cache -> cache.disable())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, failure) -> {
                            // 401 不动 Cookie：同一浏览器里刚登录的新令牌可能和带旧令牌的请求并行，清掉会把新登录一起清掉
                            // （实测）；浏览器收到 401 自己回登录页
                            response.setStatus(401);
                            response.setContentType("application/json");
                            // 代码同 web.ErrorCode.AUTH_NOT_AUTHENTICATED（auth 不依赖 web，这里写字面值；ErrorCodeTest 比对）
                            response.getWriter().write("{\"error\":\"not_authenticated\",\"code\":\"auth/not-authenticated\"}");
                        })
                        .accessDeniedHandler((request, response, denied) -> {
                            response.setStatus(403);
                            response.setContentType("application/json");
                            // 代码同 web.ErrorCode.AUTH_FORBIDDEN
                            response.getWriter().write("{\"error\":\"forbidden\",\"code\":\"auth/forbidden\"}");
                        }))
                .authorizeHttpRequests(rules -> rules
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers("/api/auth/login").permitAll()
                        // 连接程序的 WebSocket 握手：不用登录会话，由端点自己查令牌。
                        // 路径同 LocalEndpoint.PATH，这里写字面值，免得 auth 和 local 两个包互相依赖。
                        .requestMatchers("/api/local/connect").permitAll()
                        // 用户管理只有管理员
                        .requestMatchers("/api/users/**").hasRole("ADMIN")
                        // 插件的默认、锁定和按人指定的参数也只有管理员
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .addFilterBefore(new OriginFilter(), AnonymousAuthenticationFilter.class)
                // 登录的身份有了以后、授权规则之前：管理员只读查看别人时在这里换成被看的人，别的请求原样过
                .addFilterAfter(viewAs, AnonymousAuthenticationFilter.class)
                .addFilterBefore(new AuthBodyLimitFilter(), OriginFilter.class);
        return http.build();
    }

    /**
     * 不只绑定本机时，Cookie 必须带 Secure，否则会话会明文经过网络。
     * {@code server.address} 没设时 Spring 绑定全部网卡，不算回环。
     */
    public static void requireSecureCookie(String address, boolean secure) {
        if (!Hosts.isLoopback(address) && !secure) {
            throw new IllegalStateException("server.address is " + address + " but the session cookie is not Secure. "
                    + "Serve over HTTPS and set server.servlet.session.cookie.secure=true.");
        }
    }

    @Bean
    LoginContextRepository loginContextRepository(Environment environment, UserStore users) {
        boolean secure = environment.getProperty("server.servlet.session.cookie.secure", Boolean.class, false);
        requireSecureCookie(environment.getProperty("server.address"), secure);
        Duration timeout = environment.getProperty(TIMEOUT_PROPERTY, Duration.class, Duration.ofHours(12));
        return new LoginContextRepository(users, timeout, secure, Clock.systemUTC());
    }

    /** 管理员只读查看别人的会话；部署的人可以用 {@code tantalum.view-as.enabled=false} 关掉。 */
    @Bean
    ViewAs viewAs(Environment environment, UserStore users) {
        return new ViewAs(users, environment.getProperty("tantalum.view-as.enabled", Boolean.class, true), Clock.systemUTC());
    }

    /** 这个过滤器只在上面的安全链里用；不让 Spring Boot 再把它当普通过滤器注册一遍。 */
    @Bean
    org.springframework.boot.web.servlet.FilterRegistrationBean<ViewAs> viewAsOnlyInTheSecurityChain(ViewAs viewAs) {
        org.springframework.boot.web.servlet.FilterRegistrationBean<ViewAs> registration =
                new org.springframework.boot.web.servlet.FilterRegistrationBean<>(viewAs);
        registration.setEnabled(false);
        return registration;
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

    /**
     * 写操作如果带 {@code Origin}，必须和请求的主机一致；不带 {@code Origin} 时看浏览器的 {@code Sec-Fetch-Site}，
     * 标明跨站的也拒。拒绝回 JSON，控制台能按代码显示。
     */
    static final class OriginFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (!SAFE_METHODS.contains(request.getMethod()) && crossSite(request)) {
                response.setStatus(403);
                response.setContentType("application/json");
                // 代码同 web.ErrorCode.AUTH_BAD_ORIGIN（ErrorCodeTest 比对）
                response.getWriter().write("{\"error\":\"bad_origin\",\"code\":\"auth/bad-origin\"}");
                return;
            }
            chain.doFilter(request, response);
        }

        private static boolean crossSite(HttpServletRequest request) {
            String origin = request.getHeader("Origin");
            if (origin != null) {
                return !sameHost(origin, request.getServerName(), request.getServerPort(), request.getHeader("Host"));
            }
            return "cross-site".equalsIgnoreCase(request.getHeader("Sec-Fetch-Site"));
        }

        /** {@code serverName}、{@code serverPort} 是服务器认为自己被访问的地址，{@code host} 是浏览器发来的 Host 头。 */
        static boolean sameHost(String origin, String serverName, int serverPort, String host) {
            try {
                URI uri = URI.create(origin);
                return serverName.equalsIgnoreCase(uri.getHost()) && serverPort == effectivePort(uri)
                        || behindHttpsProxy(uri, host);
            } catch (IllegalArgumentException malformed) {
                return false;
            }
        }

        /**
         * 前面有只做 HTTPS、不加 {@code X-Forwarded-*} 的代理时，服务器看到的是 http 和自己的端口，对不上浏览器的
         * {@code https://主机}。这时拿浏览器发来的 {@code Host} 比：它不带端口、主机和 Origin 一样，就是同一个地址。
         * 只认 https 的默认端口；带端口的照旧按上面的规矩。
         */
        private static boolean behindHttpsProxy(URI origin, String host) {
            return "https".equals(origin.getScheme()) && origin.getPort() == -1
                    && host != null && host.equalsIgnoreCase(origin.getHost());
        }

        private static int effectivePort(URI uri) {
            if (uri.getPort() != -1) {
                return uri.getPort();
            }
            return "https".equals(uri.getScheme()) ? 443 : 80;
        }
    }
}
