package tantalum.shell.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.http.Cookie;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpRequestResponseHolder;

/** review-fixes 需求 11.2：Cookie 随最后访问时间续期，登录有一个从创建起算的绝对上限。 */
class LoginContextRepositoryTest {

    private static final Duration TIMEOUT = Duration.ofHours(12);

    @TempDir
    Path dir;

    private Instant now = Instant.parse("2026-11-07T00:00:00Z");

    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    };

    /** 没有 spring-test：请求和响应用动态代理，只实现这里用到的几个方法。 */
    private static HttpServletRequest request(String token) {
        Map<String, Object> attributes = new HashMap<>();
        return (HttpServletRequest) Proxy.newProxyInstance(HttpServletRequest.class.getClassLoader(), new Class<?>[] {HttpServletRequest.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getCookies" -> token == null ? null : new Cookie[] {new Cookie(LoginContextRepository.COOKIE, token)};
                    case "getAttribute" -> attributes.get((String) args[0]);
                    case "setAttribute" -> attributes.put((String) args[0], args[1]);
                    case "removeAttribute" -> attributes.remove((String) args[0]);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static final class Response {
        final List<String> cookies = new ArrayList<>();

        HttpServletResponse servlet() {
            return (HttpServletResponse) Proxy.newProxyInstance(HttpServletResponse.class.getClassLoader(), new Class<?>[] {HttpServletResponse.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "addHeader" -> {
                            assertEquals("Set-Cookie", args[0]);
                            cookies.add((String) args[1]);
                            yield null;
                        }
                        case "isCommitted" -> false;
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }

        String token() {
            assertEquals(1, cookies.size(), cookies.toString());
            String header = cookies.getFirst();
            assertTrue(header.startsWith(LoginContextRepository.COOKIE + "="), header);
            return header.substring(LoginContextRepository.COOKIE.length() + 1, header.indexOf(';'));
        }
    }

    private static SecurityContext load(LoginContextRepository contexts, String token, Response response) {
        return contexts.loadContext(new HttpRequestResponseHolder(request(token), response.servlet()));
    }

    @Test
    void theCookieIsRenewedWhenTheLastAccessIsRecordedAndTheLoginEndsAfterThirtyDays() throws Exception {
        try (UserStore store = UserStore.open(dir)) {
            store.add("alice", "alice-password");
            LoginContextRepository contexts = new LoginContextRepository(store, TIMEOUT, false, clock);
            HttpServletRequest login = request(null);
            Response issued = new Response();
            contexts.issue(login, "alice");
            SecurityContext context = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
            context.setAuthentication(contexts.authentication("alice"));
            contexts.saveContext(context, login, issued.servlet());
            String token = issued.token();

            // 一分钟内：登录有效，不写最后访问时间，也不重发 Cookie
            now = now.plusSeconds(30);
            Response quiet = new Response();
            assertNotNull(load(contexts, token, quiet).getAuthentication());
            assertTrue(quiet.cookies.isEmpty());

            // 过了一分钟：记最后访问时间，并用同一个令牌重发 Cookie（Max-Age 重新算）
            now = now.plus(Duration.ofMinutes(2));
            Response renewed = new Response();
            assertNotNull(load(contexts, token, renewed).getAuthentication());
            assertEquals(token, renewed.token());
            assertTrue(renewed.cookies.getFirst().contains("Max-Age=" + TIMEOUT.toSeconds()), renewed.cookies.getFirst());

            // 每 6 小时用一次（空闲上限 12 小时内），用到第 30 天还在；再过 6 小时就不在了，不管最后访问多近
            for (int hour = 6; hour <= 30 * 24; hour += 6) {
                now = Instant.parse("2026-11-07T00:00:00Z").plus(Duration.ofHours(hour));
                assertNotNull(load(contexts, token, new Response()).getAuthentication(), "hour " + hour);
            }
            now = now.plus(Duration.ofHours(6));
            Response expired = new Response();
            assertNull(load(contexts, token, expired).getAuthentication());
            assertTrue(expired.cookies.isEmpty(), "an invalid token does not touch the cookie");
        }
    }
}
