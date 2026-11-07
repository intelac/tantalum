package tantalum.shell.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 按账号和来源限制登录失败次数。只在内存里，重启后清零。
 * 一次尝试在开始时就占用计数（{@link #tryBegin}），成功后再退还，这样并发猜密码不能在慢的 Argon2 校验期间越过限额。
 */
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

    /** 检查并预占一次尝试。返回 false 表示超限，调用方不要再去校验密码。 */
    public synchronized boolean tryBegin(String account, String source) {
        if (failures("a:" + account) >= maxPerAccount || failures("s:" + source) >= maxPerSource) {
            return false;
        }
        record("a:" + account);
        record("s:" + source);
        return true;
    }

    /** 登录成功：清掉账号的计数，退还来源的这一次预占。来源的其余计数保留，否则攻击者可以用自己的账号登录来清零。 */
    public synchronized void succeeded(String account, String source) {
        windows.remove("a:" + account);
        Window current = windows.get("s:" + source);
        if (current != null && current.failures > 0) {
            current.failures--;
        }
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

    /** 表满了先清过期的，还满再按最早加进来的淘汰（review-fixes 需求 11.4）：过期的留着挤掉还在计数的，等于白白放行。 */
    private void evictOldest() {
        if (windows.size() <= MAX_KEYS) {
            return;
        }
        Instant now = clock.instant();
        windows.values().removeIf(window -> !now.isBefore(window.expiresAt));
        Iterator<String> oldest = windows.keySet().iterator();
        while (windows.size() > MAX_KEYS && oldest.hasNext()) {
            oldest.next();
            oldest.remove();
        }
    }
}
