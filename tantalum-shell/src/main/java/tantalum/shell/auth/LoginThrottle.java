package tantalum.shell.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** 按账号和来源限制登录失败次数。只在内存里，重启后清零。 */
public final class LoginThrottle {

    static final int MAX_KEYS = 10_000;

    private static final class Window {
        int failures;
        Instant expiresAt;
    }

    private final int maxPerAccount;
    private final int maxPerSource;
    private final Duration window;
    private final Clock clock;
    private final Map<String, Window> windows = new LinkedHashMap<>();

    public LoginThrottle(int maxPerAccount, int maxPerSource, Duration window, Clock clock) {
        this.maxPerAccount = maxPerAccount;
        this.maxPerSource = maxPerSource;
        this.window = window;
        this.clock = clock;
    }

    public synchronized boolean allowed(String account, String source) {
        return failures("a:" + account) < maxPerAccount && failures("s:" + source) < maxPerSource;
    }

    public synchronized void failed(String account, String source) {
        record("a:" + account);
        record("s:" + source);
    }

    /** 只清账号的计数；来源的计数保留，否则攻击者可以用自己的账号登录来清零。 */
    public synchronized void succeeded(String account) {
        windows.remove("a:" + account);
    }

    synchronized int trackedKeys() {
        return windows.size();
    }

    private int failures(String key) {
        Window current = windows.get(key);
        if (current == null) {
            return 0;
        }
        if (!clock.instant().isBefore(current.expiresAt)) {
            windows.remove(key);
            return 0;
        }
        return current.failures;
    }

    private void record(String key) {
        if (failures(key) == 0) {
            Window fresh = new Window();
            fresh.expiresAt = clock.instant().plus(window);
            windows.put(key, fresh);
            evictOldest();
        }
        windows.get(key).failures++;
    }

    private void evictOldest() {
        Iterator<String> oldest = windows.keySet().iterator();
        while (windows.size() > MAX_KEYS && oldest.hasNext()) {
            oldest.next();
            oldest.remove();
        }
    }
}
