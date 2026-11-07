package tantalum.shell.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.Proxy;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.value.id.SessionId;

/**
 * 一个用户的浏览器（web-tools W7，设计“{@code UserBrowser}”）：自己的 {@code Playwright}（带一个驱动进程）、一个无头 Chromium、
 * 一个内存里的浏览器环境。标签页、Cookie、登录状态都不和别人共用，也不写服务器磁盘；不允许下载。
 * <p>Playwright for Java 不能多线程同时调用，所以每个浏览器一把锁：所有 Playwright 调用都在持锁时进行（{@link Browsers} 负责加锁）。
 * 同一用户的几个会话共用这个环境，每个会话有自己的“当前标签页”。最多 10 个标签页，再开新的时关掉最久没用的。
 */
final class UserBrowser implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(UserBrowser.class);
    static final int MAX_TABS = 10;
    static final double NAVIGATION_MS = 30_000;
    static final double ACTION_MS = 5_000;
    private static final double LAUNCH_MS = 30_000;
    /** WebRTC 不走代理就发不了 UDP，绕不过出口。 */
    static final String WEBRTC = "--force-webrtc-ip-handling-policy=disable_non_proxied_udp";
    /** 驱动和 Chromium 继承服务器的环境变量；名字像密钥、口令、代理的都清空，浏览器进程里读不到。 */
    private static final Pattern SECRET = Pattern.compile(
            "(?i)KEY|TOKEN|SECRET|PASSW|PASSPHRASE|CREDENTIAL|CREDS|AUTH|COOKIE|PRIVATE|SIGNING|DATABASE_URL|_DSN$|_PROXY$|^PROXY");

    static final String NOT_INSTALLED = "The browser is not installed on this server. Ask the administrator to run: "
            + "java -jar tantalum.jar browser install";
    static final String CANNOT_START = "The browser could not start on this server. The administrator can find the reason in the "
            + "server log.";

    private final Playwright playwright;
    private final Browser browser;
    private final BrowserContext context;
    final ReentrantLock lock = new ReentrantLock();
    private final Map<SessionId, Page> current = new HashMap<>();
    /** 标签页最后一次被用到的顺序号，越大越新。按对象本身记，同一个页面不会因为 equals 混在一起。 */
    private final Map<Page, Long> touched = new IdentityHashMap<>();
    private long tick;
    private volatile Instant lastUsed;
    private volatile boolean closed;

    /** 测试里传 null：没有真实浏览器的空壳，只用来测名额和空闲关闭。 */
    UserBrowser(Playwright playwright, Browser browser, BrowserContext context, Clock clock) {
        this.playwright = playwright;
        this.browser = browser;
        this.context = context;
        this.lastUsed = clock.instant();
    }

    /** 起一个浏览器：驱动、Chromium（代理是出口）、内存里的环境。起不来时的消息直接给模型，原因写进日志。 */
    static UserBrowser open(BrowserDriver driver, int egressPort, boolean sandbox, Clock clock) throws Browsers.Unavailable {
        try {
            driver.ensure();
        } catch (IOException failed) {
            LOG.error("browser: cannot unpack the Playwright driver: {}", failed.getMessage());
            throw new Browsers.Unavailable(CANNOT_START);
        }
        Playwright playwright;
        try {
            playwright = Playwright.create(new Playwright.CreateOptions().setEnv(driverEnvironment(System.getenv())));
        } catch (PlaywrightException failed) {
            LOG.error("browser: the Playwright driver did not start: {}", firstLine(failed.getMessage()));
            throw new Browsers.Unavailable(CANNOT_START);
        }
        try {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(true)
                    .setChromiumSandbox(sandbox)
                    .setProxy(new Proxy("http://127.0.0.1:" + egressPort))
                    .setArgs(List.of(WEBRTC))
                    .setTimeout(LAUNCH_MS));
            BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                    .setAcceptDownloads(false)
                    .setLocale("zh-CN")
                    .setViewportSize(1280, 720));
            context.setDefaultTimeout(ACTION_MS);
            context.setDefaultNavigationTimeout(NAVIGATION_MS);
            return new UserBrowser(playwright, browser, context, clock);
        } catch (RuntimeException failed) {
            // 不只 PlaywrightException：别的运行时异常也不能把驱动进程留下（review-fixes 需求 11.11）
            String reason = failed instanceof PlaywrightException ? firstLine(failed.getMessage()) : failed.getClass().getSimpleName();
            closeQuietly(playwright);
            if (reason.contains("Executable doesn't exist")) {
                LOG.error("browser: Chromium headless shell is not installed; run: java -jar tantalum.jar browser install");
                throw new Browsers.Unavailable(NOT_INSTALLED);
            }
            LOG.error("browser: Chromium did not start (sandbox={}): {}. If this Linux host or container cannot run the Chromium "
                    + "sandbox, set tantalum.web.browser.sandbox=false (see README for the risk).", sandbox, reason);
            throw new Browsers.Unavailable(CANNOT_START);
        }
    }

    /** 给驱动进程的环境：不下载浏览器；密钥、口令、代理这类变量清空（Playwright 把它们并到服务器的环境上，清空即覆盖）。 */
    static Map<String, String> driverEnvironment(Map<String, String> system) {
        Map<String, String> env = new HashMap<>();
        for (String name : system.keySet()) {
            if (SECRET.matcher(name).find() && !name.startsWith("PLAYWRIGHT_")) {
                env.put(name, "");
            }
        }
        env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
        return env;
    }

    /** 这个会话的当前标签页；没有或已关掉时新开一个。 */
    Page page(SessionId session) {
        Page page = current.get(session);
        if (page == null || page.isClosed()) {
            page = newTab(session);
            current.put(session, page);
        }
        touch(page);
        return page;
    }

    /** 这个浏览器的全部标签页，按打开的顺序。 */
    List<Page> pages() {
        return context.pages();
    }

    /** 选中的标签页成为这个会话的当前页。 */
    void select(SessionId session, Page page) {
        current.put(session, page);
        touch(page);
        page.bringToFront();
    }

    /** 网页自己开的新窗口也算标签页：操作之后超过 10 个就关掉最久没用的，不关这个会话的当前页。 */
    void trimTabs(SessionId session) {
        List<Page> pages = context.pages();
        for (Page page : pages) {
            touched.computeIfAbsent(page, ignored -> ++tick);
        }
        touched.keySet().removeIf(Page::isClosed);
        int excess = pages.size() - MAX_TABS;
        Page keep = current.get(session);
        List<Page> oldest = new ArrayList<>(pages);
        oldest.remove(keep);
        oldest.sort((a, b) -> Long.compare(touched.getOrDefault(a, 0L), touched.getOrDefault(b, 0L)));
        for (int i = 0; i < excess && i < oldest.size(); i++) {
            try {
                oldest.get(i).close();
            } catch (PlaywrightException gone) {
                // 这一页已经没了（网页自己关的）：接着关下一页
            }
        }
    }

    private Page newTab(SessionId session) {
        List<Page> pages = context.pages();
        if (pages.size() >= MAX_TABS) {
            Page oldest = null;
            for (Page page : pages) {
                if (oldest == null || touched.getOrDefault(page, 0L) < touched.getOrDefault(oldest, 0L)) {
                    oldest = page;
                }
            }
            oldest.close();
        }
        return context.newPage();
    }

    private void touch(Page page) {
        touched.put(page, ++tick);
    }

    /** 浏览器进程和驱动还在：{@code isConnected} 之外再做一次真实的往返，断开的事件要等下一次调用才送到（设计实测）。 */
    boolean connected() {
        if (browser == null || !browser.isConnected()) {
            return false;
        }
        try {
            context.cookies("https://probe.invalid/");
            return true;
        } catch (PlaywrightException gone) {
            return false;
        }
    }

    void used(Clock clock) {
        lastUsed = clock.instant();
    }

    Instant lastUsed() {
        return lastUsed;
    }

    boolean isClosed() {
        return closed;
    }

    /** 驱动和 Chromium 一起退出（实测没有残留）。调用方持锁，或者确定没人在用。 */
    @Override
    public void close() {
        closed = true;
        current.clear();
        touched.clear();
        if (playwright != null) {
            closeQuietly(playwright);
        }
    }

    private static void closeQuietly(Playwright playwright) {
        try {
            playwright.close();
        } catch (RuntimeException gone) {
            // 进程已经没了
        }
    }

    /**
     * Playwright 的错误消息后面跟着调用日志，只要第一行。驱动送回的错误有时写成 {@code Error { message='…' name='…' stack='…' }}，
     * 这时取 {@code message} 的第一行。
     */
    static String firstLine(String message) {
        if (message == null) {
            return "";
        }
        String text = message;
        int quoted = text.indexOf("message='");
        if (text.strip().startsWith("Error {") && quoted >= 0) {
            text = text.substring(quoted + "message='".length());
        }
        String line = text.lines().filter(part -> !part.isBlank()).findFirst().orElse("").strip();
        if (line.endsWith("'")) {
            line = line.substring(0, line.length() - 1);
        }
        return line.toLowerCase(Locale.ROOT).startsWith("error: ") ? line.substring(7) : line;
    }
}
