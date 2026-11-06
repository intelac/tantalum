package tantalum.shell.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;

/**
 * 名额、懒启动、空闲关闭、关机（需求 2.4、2.5、2.8）里不需要 Chromium 的部分：启动换成不起浏览器的空壳。
 * 真实浏览器的生命周期在 {@code UserBrowserIT}（和任务 20 的 {@code BrowsersIT}）里测。
 */
@Timeout(30)
class BrowsersTest {

    private static final SessionId SESSION = new SessionId("s1");

    /** 手动拨的时钟。 */
    private static final class Dial extends Clock {
        volatile Instant now = Instant.parse("2026-11-06T10:00:00Z");

        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }

    private final Dial clock = new Dial();
    private final List<UserBrowser> launched = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger launching = new AtomicInteger();
    private final AtomicInteger mostLaunching = new AtomicInteger();
    private Browsers browsers;

    @AfterEach
    void stop() {
        if (browsers != null) {
            browsers.close();
        }
    }

    private Browsers browsers(int maxOpen) {
        browsers = new Browsers(new Browsers.Settings(maxOpen, Duration.ofMinutes(10), true), null, null, clock, () -> {
            mostLaunching.accumulateAndGet(launching.incrementAndGet(), Math::max);
            try {
                Thread.sleep(100);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            }
            launching.decrementAndGet();
            UserBrowser browser = new UserBrowser(null, null, null, clock);
            launched.add(browser);
            return browser;
        }, Duration.ofHours(1));
        return browsers;
    }

    /** 需求 2.5：8 个用户同时来、上限 2，只起 2 个，其余 6 个得到“都在用”，不挤掉别人的。 */
    @Test
    void eightUsersAtOnceNeverOpenMoreThanTheLimit() throws Exception {
        Browsers browsers = browsers(2);
        CyclicBarrier start = new CyclicBarrier(8);
        List<String> busy = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger served = new AtomicInteger();
        List<Thread> users = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Principal user = new Principal("user" + i);
            users.add(Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    browsers.use(user, SESSION, (browser, session) -> {
                        Thread.sleep(300);
                        return served.incrementAndGet();
                    });
                } catch (Browsers.Unavailable full) {
                    busy.add(full.getMessage());
                } catch (Exception unexpected) {
                    busy.add("unexpected: " + unexpected);
                }
            }));
        }
        for (Thread user : users) {
            user.join();
        }
        assertEquals(2, launched.size());
        assertTrue(mostLaunching.get() <= 2);
        assertEquals(2, served.get());
        assertEquals(6, busy.size(), busy.toString());
        assertTrue(busy.stream().allMatch("All browsers on this server are in use (2 of 2). Try again in a few minutes."::equals),
                busy.toString());
        assertEquals(2, browsers.openCount());
    }

    /** 同一个用户的几个会话同时来：只起一个浏览器，调用按锁排队。 */
    @Test
    void oneUsersCallsShareOneBrowserAndRunOneAtATime() throws Exception {
        Browsers browsers = browsers(1);
        Principal alice = new Principal("alice");
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger mostInside = new AtomicInteger();
        List<Thread> calls = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            SessionId session = new SessionId("s" + i);
            calls.add(Thread.ofVirtual().start(() -> {
                try {
                    browsers.use(alice, session, (browser, s) -> {
                        mostInside.accumulateAndGet(inside.incrementAndGet(), Math::max);
                        Thread.sleep(20);
                        return inside.decrementAndGet();
                    });
                } catch (Exception unexpected) {
                    throw new AssertionError(unexpected);
                }
            }));
        }
        for (Thread call : calls) {
            call.join();
        }
        assertEquals(1, launched.size());
        assertEquals(1, mostInside.get());
    }

    /** 需求 2.4：10 分钟没用的关掉、名额空出来，下次重开；正在用的（锁着）不关。 */
    @Test
    void idleBrowsersAreClosedButBusyOnesAreKept() throws Exception {
        Browsers browsers = browsers(2);
        Principal alice = new Principal("alice");
        Principal bob = new Principal("bob");
        browsers.use(alice, SESSION, (browser, session) -> null);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread busy = Thread.ofVirtual().start(() -> {
            try {
                browsers.use(bob, SESSION, (browser, session) -> {
                    holding.countDown();
                    release.await();
                    return null;
                });
            } catch (Exception unexpected) {
                throw new AssertionError(unexpected);
            }
        });
        assertTrue(holding.await(5, TimeUnit.SECONDS));
        clock.now = clock.now.plus(Duration.ofMinutes(9));
        browsers.sweep();
        assertEquals(2, browsers.openCount(), "not idle long enough yet");
        clock.now = clock.now.plus(Duration.ofMinutes(2));
        browsers.sweep();
        assertEquals(1, browsers.openCount(), "alice's idle browser closed, bob's busy one kept");
        assertTrue(launched.get(0).isClosed());
        assertFalse(launched.get(1).isClosed());
        release.countDown();
        busy.join();

        browsers.use(alice, SESSION, (browser, session) -> null);
        assertEquals(3, launched.size(), "alice gets a new browser");
    }

    /** 起不来（没装浏览器）：消息照原样给模型，名额还回去。 */
    @Test
    void aFailedLaunchGivesItsMessageAndFreesTheSlot() throws Exception {
        browsers = new Browsers(new Browsers.Settings(1, Duration.ofMinutes(10), true), null, null, clock, () -> {
            throw new Browsers.Unavailable(UserBrowser.NOT_INSTALLED);
        }, Duration.ofHours(1));
        Browsers.Unavailable failed = assertThrows(Browsers.Unavailable.class,
                () -> browsers.use(new Principal("alice"), SESSION, (browser, session) -> null));
        assertEquals("The browser is not installed on this server. Ask the administrator to run: java -jar tantalum.jar browser install",
                failed.getMessage());
        assertEquals(0, browsers.openCount());
    }

    /** 需求 2.8：关机关掉全部浏览器，之后的调用得到错误。 */
    @Test
    void closeClosesEveryBrowser() throws Exception {
        Browsers browsers = browsers(3);
        for (String name : List.of("a", "b", "c")) {
            browsers.use(new Principal(name), SESSION, (browser, session) -> null);
        }
        browsers.close();
        assertTrue(launched.stream().allMatch(UserBrowser::isClosed));
        assertEquals(0, browsers.openCount());
        assertThrows(Browsers.Unavailable.class, () -> browsers.use(new Principal("a"), SESSION, (browser, session) -> null));
    }

    @Test
    void maxOpenMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new Browsers.Settings(0, Duration.ofMinutes(10), true));
    }

    /** NFR Security：驱动和 Chromium 读不到服务器的密钥和代理凭据。 */
    @Test
    void theDriverEnvironmentBlanksSecretsAndProxies() {
        var env = UserBrowser.driverEnvironment(java.util.Map.of("ANTHROPIC_API_KEY", "sk", "OPENAI_API_KEY", "sk", "GITHUB_TOKEN", "t",
                "HTTPS_PROXY", "http://u:p@proxy:8080", "no_proxy", "x", "DB_PASSWORD", "p", "HOME", "/home/u", "PATH", "/bin",
                "PLAYWRIGHT_BROWSERS_PATH", "/opt/pw"));
        for (String secret : List.of("ANTHROPIC_API_KEY", "OPENAI_API_KEY", "GITHUB_TOKEN", "HTTPS_PROXY", "no_proxy", "DB_PASSWORD")) {
            assertEquals("", env.get(secret), secret);
        }
        assertFalse(env.containsKey("HOME") || env.containsKey("PATH") || env.containsKey("PLAYWRIGHT_BROWSERS_PATH"));
        assertEquals("1", env.get("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD"));
    }
}
