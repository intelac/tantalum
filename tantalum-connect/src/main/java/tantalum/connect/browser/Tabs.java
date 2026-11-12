package tantalum.connect.browser;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个开着的浏览器里的标签页（local-browser 需求 5.3、5.4）。几个会话共用这一个浏览器和登录状态，每个会话有自己的“当前标签页”。
 * 最多 {@value #MAX_TABS} 个标签页，再多时关掉最久没用的，不关发起这次调用的会话的当前页。
 * 不是线程安全的：调用方（{@link LocalBrowser}）持着浏览器的锁。
 */
class Tabs {

    static final int MAX_TABS = 10;
    /** 只记最近这么多个会话的当前页；更早的会话再来时开一个新标签页。连接程序可能连着跑很多天。 */
    static final int MAX_SESSIONS = 64;

    private final BrowserContext context;
    /** 按最近使用排序，超过 {@link #MAX_SESSIONS} 时忘掉最久没来的会话。 */
    private final Map<String, Page> current = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Page> eldest) {
            return size() > MAX_SESSIONS;
        }
    };
    /** 标签页最后一次被用到的顺序号，越大越新。按对象本身记，同一个页面不会因为 equals 混在一起。 */
    private final Map<Page, Long> touched = new IdentityHashMap<>();
    private long tick;

    Tabs(BrowserContext context) {
        this.context = context;
    }

    BrowserContext context() {
        return context;
    }

    /** 这个会话的当前标签页；没有或已关掉时新开一个。 */
    Page page(String session) {
        Page page = current.get(session);
        if (page == null || page.isClosed()) {
            page = newTab();
            current.put(session, page);
        }
        touch(page);
        return page;
    }

    /** 全部标签页，按打开的顺序。 */
    List<Page> pages() {
        return context.pages();
    }

    /** 选中的标签页成为这个会话的当前页。 */
    void select(String session, Page page) {
        current.put(session, page);
        touch(page);
        page.bringToFront();
    }

    /** 网页自己开的新窗口也算标签页：操作之后超过上限就关掉最久没用的，不关这个会话的当前页。 */
    void trim(String session) {
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
                // 这一页已经没了（网页自己关的、用户关的）：接着关下一页
            }
        }
    }

    /**
     * 浏览器刚打开时带一个空白页：没有会话在用它就拿来用，不留一个空标签。否则新开；满了先关最久没用的。
     */
    private Page newTab() {
        List<Page> pages = context.pages();
        for (Page page : pages) {
            if (!page.isClosed() && "about:blank".equals(page.url()) && !current.containsValue(page)) {
                return page;
            }
        }
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

    /** 浏览器进程和驱动还在：做一次真实的往返。断开的事件要等下一次调用才送到，只看 {@code isConnected} 不够。 */
    boolean connected() {
        try {
            context.cookies("https://probe.invalid/");
            return true;
        } catch (RuntimeException gone) {
            return false;
        }
    }
}
