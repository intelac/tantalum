package tantalum.connect.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.PlaywrightException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tantalum.connect.browser.LocalBrowser.Open;
import tantalum.local.tool.LocalResult;
import tantalum.value.json.Json;

/**
 * local-browser 需求 5：什么时候开、什么时候关，不起真浏览器（真浏览器在 LocalBrowserIT）。假的一组里“浏览器还在不在”由测试控制。
 */
class LocalBrowserTest {

    private static final Json NONE = new Json.Obj(Map.of());

    /** 能往前拨的钟。 */
    private static final class Ticking extends Clock {
        private Instant now = Instant.parse("2026-11-12T03:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override public Instant instant() { return now; }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    }

    /** 假的标签页：浏览器在不在由 {@code alive} 说了算，别的都不碰 Playwright。 */
    private static final class FakeTabs extends Tabs {
        volatile boolean alive = true;
        final List<String> trimmed = new ArrayList<>();

        FakeTabs() {
            super(null);
        }

        @Override boolean connected() { return alive; }
        @Override void trim(String session) { trimmed.add(session); }
    }

    private final Ticking clock = new Ticking();
    private final AtomicInteger launched = new AtomicInteger();
    private final AtomicInteger closedProcesses = new AtomicInteger();
    private final List<FakeTabs> opened = new ArrayList<>();
    private final List<String> ran = new ArrayList<>();
    private LocalBrowser browser;

    private Open launch() {
        launched.incrementAndGet();
        FakeTabs tabs = new FakeTabs();
        opened.add(tabs);
        return new Open(closedProcesses::incrementAndGet, tabs);
    }

    private LocalBrowser browser(boolean headless, LocalBrowser.Launcher launcher, LocalBrowser.Runner runner) {
        browser = new LocalBrowser(headless, launcher, runner, clock, Duration.ofHours(1));
        return browser;
    }

    private LocalResult ok(Tabs tabs, String session, String tool, Json arguments) {
        ran.add(session + " " + tool);
        return LocalResult.ok("ok");
    }

    @AfterEach
    void stop() {
        if (browser != null) {
            browser.close();
        }
    }

    /** 需求 5.1、5.2：造出来时什么都不起；第一次调用才起，之后一直用这一个；每次操作后收拾标签页。 */
    @Test
    void nothingStartsUntilTheFirstCallAndThenOneBrowserServesEverySession() throws Exception {
        LocalBrowser browser = browser(false, this::launch, this::ok);
        assertEquals(0, launched.get());
        assertFalse(browser.isOpen());
        assertEquals("ok", browser.call("s1", "browser_snapshot", NONE).text());
        assertEquals("ok", browser.call("s2", "browser_snapshot", NONE).text());
        assertEquals(1, launched.get());
        assertEquals(List.of("s1 browser_snapshot", "s2 browser_snapshot"), ran);
        assertEquals(List.of("s1", "s2"), opened.getFirst().trimmed);
    }

    /** 需求 5.5：有窗口的再久不用也不关；无头的空闲 10 分钟后关掉，下次调用重开。 */
    @Test
    void onlyAHeadlessBrowserClosesWhenIdle() throws Exception {
        LocalBrowser visible = browser(false, this::launch, this::ok);
        visible.call("s1", "browser_snapshot", NONE);
        clock.advance(Duration.ofHours(8));
        visible.sweep();
        assertTrue(visible.isOpen(), "a visible browser is never closed for being idle");
        visible.close();

        LocalBrowser headless = browser(true, this::launch, this::ok);
        headless.call("s1", "browser_snapshot", NONE);
        clock.advance(Duration.ofMinutes(9));
        headless.sweep();
        assertTrue(headless.isOpen(), "9 minutes: still open");
        clock.advance(Duration.ofMinutes(2));
        headless.sweep();
        assertFalse(headless.isOpen(), "more than 10 minutes without a call");
        assertEquals(2, closedProcesses.get(), "the driver process of each closed browser was ended");
        headless.call("s1", "browser_snapshot", NONE);
        assertEquals(3, launched.get(), "the next call opens it again");
    }

    /** 需求 5.6：调用进行中浏览器没了（用户关了窗口、崩溃）：这次写明原因，下次重新打开。 */
    @Test
    void aBrowserThatDiesDuringACallIsReportedAndReopenedNextTime() throws Exception {
        LocalBrowser browser = browser(false, this::launch, (tabs, session, tool, arguments) -> {
            if (tool.equals("browser_click")) {
                ((FakeTabs) tabs).alive = false;
                throw new PlaywrightException("Target page, context or browser has been closed\nCall log:\n  - secret page text");
            }
            return LocalResult.ok("ok");
        });
        browser.call("s1", "browser_snapshot", NONE);
        LocalResult died = browser.call("s1", "browser_click", NONE);
        assertTrue(died.error());
        assertEquals(LocalBrowser.CLOSED, died.text());
        assertFalse(died.text().contains("secret"));
        assertFalse(browser.isOpen());
        assertEquals("ok", browser.call("s1", "browser_snapshot", NONE).text());
        assertEquals(2, launched.get());
    }

    /** 需求 5.6：两次调用之间被用户关掉的，下一次调用直接重开，不先报一次错。 */
    @Test
    void aBrowserClosedBetweenCallsIsReopenedSilently() throws Exception {
        LocalBrowser browser = browser(false, this::launch, this::ok);
        browser.call("s1", "browser_snapshot", NONE);
        opened.getFirst().alive = false;
        LocalResult next = browser.call("s1", "browser_snapshot", NONE);
        assertFalse(next.error());
        assertEquals(2, launched.get());
        assertEquals(1, closedProcesses.get(), "what was left of the old one is cleaned up");
    }

    /** 浏览器还在时，Playwright 的错误只给第一行；参数不合格的话原样给；别的都不让浏览器重开。 */
    @Test
    void errorsOfALivingBrowserBecomeShortSentences() throws Exception {
        LocalBrowser browser = browser(false, this::launch, (tabs, session, tool, arguments) -> {
            throw tool.equals("browser_navigate")
                    ? new PlaywrightException("Timeout 30000ms exceeded.\nCall log:\n  - navigating to \"https://slow.example/\"")
                    : new IllegalArgumentException("There is no tab 9; this browser has 1 tabs.");
        });
        LocalResult slow = browser.call("s1", "browser_navigate", NONE);
        assertTrue(slow.error());
        assertEquals("Timed out after 30 s waiting for the page.", slow.text());
        assertEquals("There is no tab 9; this browser has 1 tabs.", browser.call("s1", "browser_tabs", NONE).text());
        assertEquals(1, launched.get());
        assertTrue(browser.isOpen());
    }

    /** 需求 3.7、5.10：起不来时把那句话给模型，下次调用再试；不留半开的状态。 */
    @Test
    void aBrowserThatCannotStartIsAnErrorResultEachTime() throws Exception {
        LocalBrowser browser = browser(false, () -> {
            launched.incrementAndGet();
            throw new LocalBrowser.Unavailable(LocalBrowser.IN_USE);
        }, this::ok);
        for (int i = 0; i < 2; i++) {
            LocalResult result = browser.call("s1", "browser_snapshot", NONE);
            assertTrue(result.error());
            assertEquals(LocalBrowser.IN_USE, result.text());
        }
        assertEquals(2, launched.get());
        assertEquals(List.of(), ran);
        assertFalse(browser.isOpen());
    }

    /** 需求 5.2、5.9：调用一个接一个；等着的那个被取消（线程被打断）马上退出，不等前一个跑完，也不碰浏览器。 */
    @Test
    void callsRunOneAtATimeAndAWaitingCallCanBeCancelled() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        LocalBrowser browser = browser(false, this::launch, (tabs, session, tool, arguments) -> {
            ran.add(session);
            if (session.equals("slow")) {
                inside.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            return LocalResult.ok("ok");
        });
        Thread slow = Thread.ofVirtual().start(() -> {
            try {
                browser.call("slow", "browser_wait_for", NONE);
            } catch (InterruptedException ignored) {
                // 不会发生
            }
        });
        assertTrue(inside.await(5, TimeUnit.SECONDS));
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread waiting = Thread.ofVirtual().start(() -> {
            try {
                browser.call("waiting", "browser_snapshot", NONE);
            } catch (Throwable thrown) {
                outcome.set(thrown);
            }
        });
        Thread.sleep(100);
        waiting.interrupt();
        waiting.join(5000);
        assertFalse(waiting.isAlive(), "the cancelled call left at once");
        assertTrue(outcome.get() instanceof InterruptedException, String.valueOf(outcome.get()));
        release.countDown();
        slow.join(5000);
        assertEquals(List.of("slow"), ran, "the cancelled call never touched the browser");
        assertEquals("ok", browser.call("s1", "browser_snapshot", NONE).text(), "the lock was released");
    }

    /** 需求 5.7：连接程序退出时浏览器和驱动一起结束；之后的调用被拒绝，不会再起一个。 */
    @Test
    void closingEndsTheBrowserAndRefusesLaterCalls() throws Exception {
        LocalBrowser browser = browser(true, this::launch, this::ok);
        browser.call("s1", "browser_snapshot", NONE);
        browser.close();
        assertEquals(1, closedProcesses.get());
        LocalResult late = browser.call("s1", "browser_snapshot", NONE);
        assertTrue(late.error());
        assertEquals(LocalBrowser.STOPPING, late.text());
        assertEquals(1, launched.get());
    }

    /** 需求 6.5：浏览器进程拿不到连接程序的令牌和别的密钥；也不让驱动去下载浏览器。 */
    @Test
    void theBrowserProcessGetsNoSecretsFromTheConnectorsEnvironment() {
        Map<String, String> env = LocalBrowser.driverEnvironment(Map.of("TANTALUM_TOKEN", "tc_secret", "ANTHROPIC_API_KEY", "sk",
                "DB_PASSWORD", "pw", "PATH", "/usr/bin", "HOME", "/Users/yi", "HTTPS_PROXY", "http://proxy:3128",
                "PLAYWRIGHT_BROWSERS_PATH", "/x"));
        assertEquals("", env.get("TANTALUM_TOKEN"));
        assertEquals("", env.get("ANTHROPIC_API_KEY"));
        assertEquals("", env.get("DB_PASSWORD"));
        assertFalse(env.containsKey("PATH"), "ordinary variables are inherited untouched");
        assertFalse(env.containsKey("HTTPS_PROXY"), "requirement 6.4: the system's own proxy settings stay");
        assertFalse(env.containsKey("PLAYWRIGHT_BROWSERS_PATH"));
        assertEquals("1", env.get("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD"));
    }

    /** 设计：同一个资料目录被另一个浏览器进程占着时 Chromium 的几种说法都认得；别的启动错误不算。 */
    @Test
    void aProfileInUseIsRecognised() {
        assertTrue(LocalBrowser.profileInUse("Error {\n  message='Failed to create a ProcessSingleton for your profile directory. "
                + "This usually means that the profile is already in use by another instance of Chromium."));
        assertTrue(LocalBrowser.profileInUse("Failed to create /x/SingletonLock: File exists"));
        assertFalse(LocalBrowser.profileInUse("Failed to launch: spawn ENOENT"));
    }

    /** 搬自服务器的 BrowserToolsTest：Playwright 的错误只给第一行，不给调用日志。 */
    @Test
    void playwrightErrorsBecomeShortSentences() {
        assertEquals("Timed out after 30 s waiting for the page.", Actions.describe(new PlaywrightException(
                "Timeout 30000ms exceeded.\nCall log:\n  - navigating to \"https://slow.example/\"")));
        assertEquals("This URL starts a file download; downloads are not allowed.",
                Actions.describe(new PlaywrightException("Error: Download is starting\nCall log:\n  - navigating")));
        assertEquals("Element is not visible", Actions.describe(new PlaywrightException("Error: Element is not visible\nsecret call log")));
        // 驱动送回的结构化错误
        assertEquals("This URL starts a file download; downloads are not allowed.", Actions.describe(new PlaywrightException(
                "Error {\n  message='Download is starting\n  name='Error\n  stack='Error: Download is starting\n    at x.js:1\n}")));
        assertEquals("The browser reported an error without a message.", Actions.describe(new PlaywrightException("")));
    }
}
