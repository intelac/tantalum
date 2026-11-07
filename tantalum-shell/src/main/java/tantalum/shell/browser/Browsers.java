package tantalum.shell.browser;

import com.microsoft.playwright.PlaywrightException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tantalum.shell.attachment.ToolImages;
import tantalum.shell.net.Egress;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;

/**
 * 每个用户一个浏览器（设计“{@code Browsers}”和“浏览器的生命周期”）：
 * <ul>
 *   <li>懒启动：用户第一次调浏览器工具时才起；同一用户同时来的几个调用只起一个。</li>
 * <li>上限：开着的（含正在起的）到了 {@code maxOpen}，新用户得到“都在用”，不挤掉别人的。计数和占名额在同一把锁里，
 *       起浏览器本身在锁外。</li>
 * <li>空闲关闭：后台虚拟线程每 30 秒看一次，最后一次调用结束超过 {@code idleClose}、锁也空着的关掉。</li>
 *   <li>崩溃：调用抛 {@link PlaywrightException} 并且浏览器断了，关掉这一组、名额空出来，这次返回崩溃的说明，下次重开；别人不受影响。</li>
 * </ul>
 * 所有 Playwright 调用都在这个用户的浏览器锁里，锁在 {@code finally} 里放掉，调用被取消、超时也一样。
 */
public final class Browsers implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(Browsers.class);
    private static final Duration SWEEP = Duration.ofSeconds(30);
    private static final long CLOSE_WAIT_SECONDS = 5;
    static final String CRASHED = "The browser crashed and was closed; its pages and logins are gone. Call the tool again to open a new one.";

    /** 管理员的配置：同时开几个、空闲多久关、Chromium 渲染进程的沙箱。 */
    public record Settings(int maxOpen, Duration idleClose, boolean sandbox) {

        public Settings {
            if (maxOpen < 1) {
                throw new IllegalArgumentException("tantalum.web.browser.max-open must be at least 1");
            }
        }
    }

    /** 浏览器用不了：都在用、没装、起不来、崩溃了。消息直接给模型。 */
    public static final class Unavailable extends Exception {
        public Unavailable(String message) {
            super(message);
        }
    }

    /** 持锁时对这个用户的浏览器做的事。 */
    @FunctionalInterface
    interface Action<T> {
        T run(UserBrowser browser, SessionId session) throws Exception;
    }

    /** 起一个浏览器。测试里换成不起 Chromium 的。 */
    interface Launcher {
        UserBrowser launch() throws Unavailable;
    }

    private final Settings settings;
    private final ToolImages images;
    private final Egress egress;
    private final Clock clock;
    private final Launcher launcher;
    /** 每个用户的浏览器；还在起的是没完成的 future，也占名额。 */
    private final Map<Principal, CompletableFuture<UserBrowser>> open = new HashMap<>();
    private final Thread sweeper;
    private boolean closed;

    public Browsers(Settings settings, Egress egress, ToolImages images, Clock clock, BrowserDriver driver) {
        this(settings, egress, images, clock, () -> UserBrowser.open(driver, egress.port(), settings.sandbox(), clock), SWEEP);
    }

    Browsers(Settings settings, Egress egress, ToolImages images, Clock clock, Launcher launcher, Duration sweep) {
        this.settings = settings;
        this.egress = egress;
        this.images = images;
        this.clock = clock;
        this.launcher = launcher;
        this.sweeper = Thread.ofVirtual().name("browser-idle").start(() -> sweepEvery(sweep));
    }

    Settings settings() {
        return settings;
    }

    /** 截图存进本人附件用。 */
    ToolImages images() {
        return images;
    }

    /** 浏览器打开网址前的事先检查用（真正拦的是出口）。 */
    Egress egress() {
        return egress;
    }

    /** 取得或启动这个用户的浏览器，加锁执行 {@code action}，放锁。 */
    <T> T use(Principal user, SessionId session, Action<T> action) throws Exception {
        while (true) {
            UserBrowser browser = acquire(user);
            browser.lock.lockInterruptibly();
            try {
                if (browser.isClosed()) {
                    continue; // 拿到之后、加锁之前被空闲关闭了：重新取一个
                }
                try {
                    return action.run(browser, session);
                } catch (RuntimeException failure) {
                    // 不只 PlaywrightException：驱动没了时别的运行时异常也可能冒出来，都先看浏览器还在不在
                    if (!browser.connected()) {
                        LOG.warn("browser of {} crashed and was closed ({})", user.value(), failure.getClass().getSimpleName());
                        discard(user, browser);
                        throw new Unavailable(CRASHED);
                    }
                    throw failure;
                }
            } finally {
                browser.used(clock);
                browser.lock.unlock();
            }
        }
    }

    private UserBrowser acquire(Principal user) throws Unavailable, InterruptedException {
        CompletableFuture<UserBrowser> slot;
        boolean mine = false;
        synchronized (this) {
            if (closed) {
                throw new Unavailable("The browser service is shutting down.");
            }
            slot = open.get(user);
            if (slot == null) {
                if (open.size() >= settings.maxOpen()) {
                    throw new Unavailable("All browsers on this server are in use (" + open.size() + " of " + settings.maxOpen()
                            + "). Try again in a few minutes.");
                }
                slot = new CompletableFuture<>();
                open.put(user, slot);
                mine = true;
            }
        }
        if (mine) {
            launch(user, slot);
        }
        try {
            return slot.get();
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof Unavailable unavailable) {
                throw new Unavailable(unavailable.getMessage());
            }
            throw new Unavailable(UserBrowser.CANNOT_START);
        }
    }

    /** 在锁外起浏览器，名额已经占住了。起不来就把名额还回去。 */
    private void launch(Principal user, CompletableFuture<UserBrowser> slot) {
        long started = System.nanoTime();
        UserBrowser browser;
        try {
            browser = launcher.launch();
        } catch (Unavailable | RuntimeException | Error failed) {
            synchronized (this) {
                open.remove(user, slot);
            }
            slot.completeExceptionally(failed);
            if (failed instanceof Error fatal) {
                throw fatal;
            }
            return;
        }
        boolean shuttingDown;
        synchronized (this) {
            shuttingDown = closed;
            if (shuttingDown) {
                open.remove(user, slot);
            }
        }
        if (shuttingDown) {
            browser.close();
            slot.completeExceptionally(new Unavailable("The browser service is shutting down."));
            return;
        }
        LOG.info("browser opened for {} in {} ms", user.value(), Duration.ofNanos(System.nanoTime() - started).toMillis());
        slot.complete(browser);
    }

    /** 崩溃的浏览器：从登记处删掉，名额空出来，关掉这一组。调用方持着它的锁。 */
    private void discard(Principal user, UserBrowser browser) {
        synchronized (this) {
            CompletableFuture<UserBrowser> slot = open.get(user);
            if (slot != null && ready(slot) == browser) {
                open.remove(user);
            }
        }
        closeQuietly(user, browser);
    }

    private void sweepEvery(Duration interval) {
        try {
            while (true) {
                Thread.sleep(interval);
                try {
                    sweep();
                } catch (RuntimeException failure) {
                    // 一次清扫出错不能让清扫线程死掉，否则空闲的浏览器再也关不掉
                    LOG.warn("browser sweep failed: {}", failure.getClass().getSimpleName(), failure);
                }
            }
        } catch (InterruptedException stopped) {
            // 关机
        }
    }

    /** 槽位里起好的浏览器；还在起、或者起失败了（{@code getNow} 抛 CompletionException）都算没有。 */
    private static UserBrowser ready(CompletableFuture<UserBrowser> slot) {
        try {
            return slot.isCompletedExceptionally() ? null : slot.getNow(null);
        } catch (RuntimeException failed) {
            return null;
        }
    }

    /** 关一个浏览器，失败只记日志：一个关不掉不影响其余的。 */
    private static void closeQuietly(Principal user, UserBrowser browser) {
        try {
            browser.close();
        } catch (RuntimeException failure) {
            LOG.warn("browser of {} did not close cleanly: {}", user.value(), failure.getClass().getSimpleName());
        }
    }

    /** 空闲超过 {@code idleClose} 并且锁空着的关掉；正在用的不动。 */
    void sweep() {
        List<Map.Entry<Principal, UserBrowser>> idle = new ArrayList<>();
        synchronized (this) {
            var entries = open.entrySet().iterator();
            while (entries.hasNext()) {
                var entry = entries.next();
                UserBrowser browser = ready(entry.getValue());
                if (browser == null) {
                    continue;
                }
                boolean expired = Duration.between(browser.lastUsed(), clock.instant()).compareTo(settings.idleClose()) > 0;
                if (expired && browser.lock.tryLock()) {
                    entries.remove();
                    idle.add(Map.entry(entry.getKey(), browser));
                    LOG.info("browser of {} closed after {} without use", entry.getKey().value(), settings.idleClose());
                }
            }
        }
        for (Map.Entry<Principal, UserBrowser> entry : idle) {
            try {
                closeQuietly(entry.getKey(), entry.getValue());
            } finally {
                entry.getValue().lock.unlock();
            }
        }
    }

    /** 开着的（含正在起的）浏览器数，给日志和测试。 */
    public synchronized int openCount() {
        return open.size();
    }

    /** 关掉全部浏览器。正在用的最多等 5 秒，到时间也关。一个关不掉不影响其余的。 */
    @Override
    public void close() {
        List<Map.Entry<Principal, CompletableFuture<UserBrowser>>> all;
        synchronized (this) {
            closed = true;
            all = new ArrayList<>(open.entrySet());
            open.clear();
        }
        sweeper.interrupt();
        for (Map.Entry<Principal, CompletableFuture<UserBrowser>> entry : all) {
            UserBrowser browser = ready(entry.getValue());
            if (browser == null) {
                continue; // 还在起：起好后看到 closed 自己关；起失败的没有东西可关
            }
            boolean locked = false;
            try {
                locked = browser.lock.tryLock(CLOSE_WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            try {
                closeQuietly(entry.getKey(), browser);
            } finally {
                if (locked) {
                    browser.lock.unlock();
                }
            }
        }
    }
}
