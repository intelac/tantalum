package tantalum.connect.browser;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import tantalum.local.tool.Browser;
import tantalum.local.tool.LocalResult;
import tantalum.value.json.Json;

/**
 * 这台电脑上的浏览器（local-browser 需求 3、5）：用户已装的 Chrome 或 Edge，连接程序自己的资料目录，登录状态留在盘上。
 * <ul>
 *   <li>懒启动：第一次有 {@code browser_*} 调用时才起驱动和浏览器，连接程序启动时什么都不起。</li>
 *   <li>一个连接程序只有一个浏览器，一把锁：调用一个接一个执行（Playwright for Java 不能多线程同时调用）。
 *       等锁能被打断，服务器取消、断开时等着的调用马上退出；已经在跑的那一步跑完（最多 30 秒）才放锁。</li>
 *   <li>有窗口的不因为空闲而关；无头的空闲 {@link #IDLE} 后关掉，下次调用重开（同 {@code @playwright/mcp} 的规矩）。</li>
 *   <li>用户关掉了窗口、浏览器崩溃：正在进行的调用写明原因；下一次调用重新打开，登录状态还在，标签页没了。</li>
 * </ul>
 * 起不来的原因只打在这台电脑的终端里，给服务器的是固定的话（本机的错误消息可能带路径）。
 */
public final class LocalBrowser implements Browser, AutoCloseable {

    static final Duration IDLE = Duration.ofMinutes(10);
    private static final Duration SWEEP = Duration.ofSeconds(30);
    private static final double LAUNCH_MS = 30_000;
    static final String CLOSED = "The browser was closed or crashed. Call the tool again to open a new one; logins are kept, "
            + "open tabs are gone.";
    static final String IN_USE = "The browser profile on this computer is in use by another program. Close the other Tantalum "
            + "Connect or browser that uses it.";
    static final String CANNOT_START = "The browser could not start on this computer; see the connector's output.";
    static final String STOPPING = "The connector on this computer is shutting down.";
    /** 驱动和浏览器继承连接程序的环境变量；名字像密钥、口令、令牌的都清空，浏览器进程里读不到（包括 {@code TANTALUM_TOKEN}）。 */
    private static final Pattern SECRET = Pattern.compile(
            "(?i)KEY|TOKEN|SECRET|PASSW|PASSPHRASE|CREDENTIAL|CREDS|AUTH|COOKIE|PRIVATE|SIGNING|DATABASE_URL|_DSN$");

    /** 用哪个浏览器、有没有窗口、资料目录在哪。 */
    public record Settings(Path executable, String name, boolean headless, Path profile) {}

    /** 浏览器用不了。消息直接给模型。 */
    static final class Unavailable extends Exception {
        Unavailable(String message) {
            super(message);
        }
    }

    /** 开着的一组：驱动进程、浏览器、标签页。 */
    record Open(AutoCloseable process, Tabs tabs) {}

    /** 起一个浏览器。测试里换成不起真浏览器的。 */
    @FunctionalInterface
    interface Launcher {
        Open launch() throws Unavailable;
    }

    /** 在开着的浏览器上执行一个工具。测试里换成假的。 */
    @FunctionalInterface
    interface Runner {
        LocalResult run(Tabs tabs, String session, String tool, Json arguments);
    }

    private final boolean headless;
    private final Launcher launcher;
    private final Runner runner;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private final Thread sweeper;
    private Open open;
    private volatile Instant lastUsed;
    private volatile boolean closed;
    private volatile int launches;

    public LocalBrowser(Settings settings, Driver driver, Clock clock) {
        this(settings.headless(), () -> launch(settings, driver), Actions::run, clock, SWEEP);
    }

    LocalBrowser(boolean headless, Launcher launcher, Runner runner, Clock clock, Duration sweep) {
        this.headless = headless;
        this.launcher = launcher;
        this.runner = runner;
        this.clock = clock;
        this.lastUsed = clock.instant();
        // 有窗口的不自动关，用不着清扫
        this.sweeper = headless ? Thread.ofVirtual().name("browser-idle").start(() -> sweepEvery(sweep)) : null;
    }

    @Override
    public LocalResult call(String session, String tool, Json arguments) throws InterruptedException {
        lock.lockInterruptibly();
        try {
            if (closed) {
                return LocalResult.error(STOPPING);
            }
            Tabs tabs;
            try {
                tabs = ensureOpen();
            } catch (Unavailable unavailable) {
                return LocalResult.error(unavailable.getMessage());
            }
            try {
                LocalResult result = runner.run(tabs, session, tool, arguments);
                tabs.trim(session);
                return result;
            } catch (IllegalArgumentException invalid) {
                return LocalResult.error(invalid.getMessage());
            } catch (RuntimeException failure) {
                // 不只 PlaywrightException：驱动没了时别的运行时异常也可能冒出来，都先看浏览器还在不在
                if (!tabs.connected()) {
                    note("the browser was closed or crashed (" + failure.getClass().getSimpleName() + ")");
                    discard();
                    return LocalResult.error(CLOSED);
                }
                if (failure instanceof PlaywrightException playwright) {
                    return LocalResult.error(Actions.describe(playwright));
                }
                throw failure;
            }
        } finally {
            lastUsed = clock.instant();
            lock.unlock();
        }
    }

    /** 开着就用；上次调用之后被用户关掉了、崩溃了，就悄悄重开。调用方持锁。 */
    private Tabs ensureOpen() throws Unavailable {
        if (open != null && !open.tabs().connected()) {
            discard();
        }
        if (open == null) {
            long started = System.nanoTime();
            open = launcher.launch();
            launches++;
            note("browser opened in " + Duration.ofNanos(System.nanoTime() - started).toMillis() + " ms");
        }
        return open.tabs();
    }

    /** 关掉这一组并忘掉它。调用方持锁。 */
    private void discard() {
        Open old = open;
        open = null;
        if (old != null) {
            try {
                old.process().close();
            } catch (Exception gone) {
                // 进程已经没了
            }
        }
    }

    private void sweepEvery(Duration interval) {
        try {
            while (!closed) {
                Thread.sleep(interval);
                try {
                    sweep();
                } catch (RuntimeException failure) {
                    // 一次清扫出错不能让清扫线程死掉，否则空闲的浏览器再也关不掉
                    note("browser idle check failed: " + failure.getClass().getSimpleName());
                }
            }
        } catch (InterruptedException stopped) {
            // 连接程序退出
        }
    }

    /** 无头的浏览器空闲超过 {@link #IDLE}、锁也空着：关掉。有窗口的不关。 */
    void sweep() {
        if (!headless || !lock.tryLock()) {
            return;
        }
        try {
            if (open != null && Duration.between(lastUsed, clock.instant()).compareTo(IDLE) > 0) {
                note("browser closed after " + IDLE.toMinutes() + " minutes without use");
                discard();
            }
        } finally {
            lock.unlock();
        }
    }

    /** 现在有没有开着的浏览器（给测试）。 */
    boolean isOpen() {
        lock.lock();
        try {
            return open != null;
        } finally {
            lock.unlock();
        }
    }

    /** 一共起过几次浏览器（给测试）。 */
    int launches() {
        return launches;
    }

    /** 连接程序退出：之后的调用一律拒绝；正在跑的那一步最多等 5 秒，到时间也关。驱动和浏览器一起结束。 */
    @Override
    public void close() {
        closed = true;
        if (sweeper != null) {
            sweeper.interrupt();
        }
        boolean locked = false;
        try {
            locked = lock.tryLock(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        try {
            discard();
        } finally {
            if (locked) {
                lock.unlock();
            }
        }
    }

    /** 起驱动和浏览器。失败时不留驱动进程；给模型的话是固定的，原因的第一行打在终端。 */
    private static Open launch(Settings settings, Driver driver) throws Unavailable {
        try {
            driver.ensure();
        } catch (IOException | RuntimeException failed) {
            note("cannot prepare the browser driver: " + failed.getMessage());
            throw new Unavailable(CANNOT_START);
        }
        Playwright playwright;
        try {
            playwright = Playwright.create(new Playwright.CreateOptions().setEnv(driverEnvironment(System.getenv())));
        } catch (RuntimeException failed) {
            note("the browser driver did not start: " + Actions.firstLine(failed.getMessage()));
            throw new Unavailable(CANNOT_START);
        }
        try {
            BrowserType.LaunchPersistentContextOptions options = new BrowserType.LaunchPersistentContextOptions()
                    .setExecutablePath(settings.executable())
                    .setHeadless(settings.headless())
                    .setAcceptDownloads(false)
                    .setTimeout(LAUNCH_MS);
            if (settings.headless()) {
                options.setViewportSize(1280, 720);
            } else {
                // 有窗口时页面跟着窗口的大小，用户拖大拖小都算数
                options.setViewportSize(null);
            }
            BrowserContext context = playwright.chromium().launchPersistentContext(settings.profile(), options);
            context.setDefaultTimeout(Actions.ACTION_MS);
            context.setDefaultNavigationTimeout(Actions.NAVIGATION_MS);
            // 先正常关浏览器（Cookie 这时才全部落盘），再结束驱动；浏览器已经没了时第一步会失败，照样结束驱动
            return new Open(() -> {
                try {
                    context.close();
                } catch (RuntimeException gone) {
                    // 浏览器已经没了
                } finally {
                    playwright.close();
                }
            }, new Tabs(context));
        } catch (RuntimeException failed) {
            String reason = failed instanceof PlaywrightException ? Actions.firstLine(failed.getMessage()) : failed.getClass().getSimpleName();
            try {
                playwright.close();
            } catch (RuntimeException gone) {
                // 进程已经没了
            }
            if (profileInUse(String.valueOf(failed.getMessage()), settings.profile())) {
                note("the browser profile " + settings.profile() + " is in use by another program");
                throw new Unavailable(IN_USE);
            }
            note(settings.name() + " did not start: " + reason);
            throw new Unavailable(CANNOT_START);
        }
    }

    /**
     * 同一个资料目录只能给一个浏览器进程用。第二个启动时，macOS、Linux 上 Chromium 明说资料目录被占；Windows 上它把事情交给已经开着的
     * 那个进程、自己马上退出，Playwright 只看到“浏览器已经关了”（2026-11-12 Windows CI 实测）。后一种情况再看资料目录里有没有
     * 浏览器运行时留的锁，有才算被占，别的启动失败不算。
     */
    static boolean profileInUse(String message, Path profile) {
        return profileInUse(message) || (message.contains("has been closed") && locked(profile));
    }

    /** Chromium 明说资料目录被占的几种话。 */
    static boolean profileInUse(String message) {
        return message.contains("ProcessSingleton") || message.contains("SingletonLock") || message.contains("profile is already in use")
                || message.contains("profile appears to be in use");
    }

    /** 资料目录里有浏览器运行时的锁：Windows 上是 {@code lockfile}（进程一结束系统就删掉），macOS、Linux 上是 {@code SingletonLock}。 */
    static boolean locked(Path profile) {
        return Files.exists(profile.resolve("lockfile"), LinkOption.NOFOLLOW_LINKS)
                || Files.exists(profile.resolve("SingletonLock"), LinkOption.NOFOLLOW_LINKS);
    }

    /** 给驱动进程的环境：不下载浏览器；密钥、口令、令牌这类变量清空（Playwright 把它们并到连接程序的环境上，清空即覆盖）。 */
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

    /** 细节只打在这台电脑的终端里，不送给服务器。 */
    private static void note(String text) {
        System.err.println("[tantalum-connect] " + text);
    }
}
