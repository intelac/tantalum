package tantalum.connect.browser;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.AriaSnapshotMode;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitForSelectorState;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tantalum.local.browser.BrowserArguments;
import tantalum.local.browser.PageText;
import tantalum.local.browser.PageUrl;
import tantalum.local.tool.LocalImage;
import tantalum.local.tool.LocalResult;
import tantalum.value.json.Json;

/**
 * 11 个浏览器工具在这台电脑上做的事（local-browser 需求 4；定义在 {@code tantalum.local.tool.BrowserTools}）。
 * 操作之后返回新的快照（同 {@code @playwright/mcp} 默认的 full 快照），少一轮“操作完再读一次”。
 * 调用方持着浏览器的锁；参数的格式已经由工具的定义查过，网址在这里再查一遍（不只靠服务器和定义）。
 */
final class Actions {

    static final double NAVIGATION_MS = 30_000;
    static final double ACTION_MS = 5_000;
    private static final double WAIT_MS = 30_000;
    private static final double SETTLE_MS = 250;
    private static final Pattern TIMEOUT = Pattern.compile("Timeout (\\d+)ms exceeded");
    /** {@code browser_evaluate} 的返回值写成 JSON：键的顺序照页面给的，NaN、Infinity 这类也能写。 */
    private static final Gson JSON = new GsonBuilder().serializeNulls().disableHtmlEscaping()
            .serializeSpecialFloatingPointValues().create();

    private Actions() {}

    /** 执行一个工具。参数不合格抛 {@link IllegalArgumentException}；Playwright 的错误原样抛出，由调用方换成给模型的话。 */
    static LocalResult run(Tabs tabs, String session, String tool, Json arguments) {
        Page page = tabs.page(session);
        switch (tool) {
            case "browser_navigate" -> {
                String url = PageUrl.check(BrowserArguments.required(arguments, "url")).toString();
                page.navigate(url);
                // 跳转了要说明，否则模型会以为没打开对
                return snapshot(tabs, session, url);
            }
            case "browser_navigate_back" -> page.goBack();
            case "browser_snapshot" -> { }
            case "browser_click" -> {
                element(page, arguments).click();
                settle(page);
            }
            case "browser_type" -> {
                Locator element = element(page, arguments);
                element.fill(BrowserArguments.required(arguments, "text"));
                if (BrowserArguments.flag(arguments, "submit")) {
                    element.press("Enter");
                    settle(page);
                }
            }
            case "browser_press_key" -> {
                page.keyboard().press(BrowserArguments.required(arguments, "key"));
                settle(page);
            }
            case "browser_select_option" ->
                    element(page, arguments).selectOption(BrowserArguments.values(arguments).toArray(String[]::new));
            case "browser_wait_for" -> waitFor(page, arguments);
            case "browser_take_screenshot" -> {
                return screenshot(page, arguments);
            }
            case "browser_tabs" -> {
                return tabs(tabs, session, page, arguments);
            }
            case "browser_evaluate" -> {
                return LocalResult.ok(PageText.evaluated(json(page.evaluate(BrowserArguments.required(arguments, "expression")))));
            }
            default -> throw new IllegalArgumentException("Unknown tool: " + tool);
        }
        return snapshot(tabs, session, null);
    }

    /** 快照类结果，取本会话的当前页（点了链接开出新窗口时，当前页不变）。{@code requested} 是 browser_navigate 要打开的网址。 */
    private static LocalResult snapshot(Tabs tabs, String session, String requested) {
        Page page = tabs.page(session);
        String aria = page.ariaSnapshot(new Page.AriaSnapshotOptions().setMode(AriaSnapshotMode.AI));
        List<Page> pages = tabs.pages();
        return LocalResult.ok(PageText.snapshot(page.title(), page.url(), index(pages, page), pages.size(), aria, requested));
    }

    /**
     * 点击、回车之后等一下：网页开的新窗口、开始的跳转要等 Playwright 下一次和驱动往返时才知道（事件只在调用时处理）。
     * 先等 250 ms，再等页面的 DOM 加载完，最多 5 秒；等不到也照常返回快照。
     */
    private static void settle(Page page) {
        page.waitForTimeout(SETTLE_MS);
        try {
            page.waitForLoadState(LoadState.DOMCONTENTLOADED, new Page.WaitForLoadStateOptions().setTimeout(ACTION_MS));
        } catch (TimeoutError slow) {
            // 还在加载：快照里是当时的样子
        }
    }

    private static int index(List<Page> pages, Page page) {
        for (int i = 0; i < pages.size(); i++) {
            if (pages.get(i) == page) {
                return i + 1;
            }
        }
        return pages.size();
    }

    /** 编号指向的元素。不在当前页上时马上报错（不让 Playwright 等到超时）。 */
    private static Locator element(Page page, Json arguments) {
        String ref = BrowserArguments.ref(arguments);
        Locator element = page.getByRef(ref);
        if (element.count() == 0) {
            throw new IllegalArgumentException(BrowserArguments.staleRef(ref));
        }
        return element;
    }

    private static void waitFor(Page page, Json arguments) {
        BrowserArguments.checkWait(arguments);
        Optional<Double> seconds = BrowserArguments.number(arguments, "seconds");
        if (seconds.isPresent()) {
            page.waitForTimeout(seconds.get() * 1000);
        }
        Optional<String> text = BrowserArguments.optional(arguments, "text");
        if (text.isPresent()) {
            page.getByText(text.get()).first().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE).setTimeout(WAIT_MS));
        }
        Optional<String> gone = BrowserArguments.optional(arguments, "textGone");
        if (gone.isPresent()) {
            page.getByText(gone.get()).first().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.HIDDEN).setTimeout(WAIT_MS));
        }
    }

    /** 截图送回服务器存进本人附件，建议的名字 {@code screenshot-<主机>.png}；文字是那句“不可信”加截的是哪个网址。 */
    private static LocalResult screenshot(Page page, Json arguments) {
        byte[] png = page.screenshot(new Page.ScreenshotOptions().setFullPage(BrowserArguments.flag(arguments, "fullPage")));
        String url = page.url();
        String host;
        try {
            host = URI.create(url).getHost();
        } catch (IllegalArgumentException odd) {
            host = null;
        }
        String name = "screenshot-" + (host == null || host.isEmpty() ? "page" : host) + ".png";
        return LocalResult.image(PageText.screenshot(url), new LocalImage("image/png", png, name));
    }

    private static LocalResult tabs(Tabs tabs, String session, Page current, Json arguments) {
        BrowserArguments.checkTabs(arguments);
        List<Page> pages = tabs.pages();
        if (BrowserArguments.required(arguments, "action").equals("select")) {
            int index = BrowserArguments.number(arguments, "index").orElseThrow().intValue();
            if (index < 1 || index > pages.size()) {
                throw new IllegalArgumentException("There is no tab " + index + "; this browser has " + pages.size() + " tabs.");
            }
            tabs.select(session, pages.get(index - 1));
            return snapshot(tabs, session, null);
        }
        List<PageText.Tab> listed = new ArrayList<>();
        for (Page page : pages) {
            listed.add(new PageText.Tab(page.title(), page.url()));
        }
        return LocalResult.ok(PageText.tabs(listed, index(pages, current)));
    }

    private static String json(Object value) {
        try {
            return JSON.toJson(value);
        } catch (RuntimeException unusual) {
            return JSON.toJson(String.valueOf(value));
        }
    }

    /** Playwright 的错误换成给模型的话：只给第一行，不给调用日志（里面可能有页面内容）。 */
    static String describe(PlaywrightException failure) {
        String line = firstLine(failure.getMessage());
        Matcher timeout = TIMEOUT.matcher(line);
        if (timeout.find()) {
            long seconds = Math.max(1, Long.parseLong(timeout.group(1)) / 1000);
            return "Timed out after " + seconds + " s waiting for the page.";
        }
        if (line.contains("Download is starting")) {
            return "This URL starts a file download; downloads are not allowed.";
        }
        return line.isEmpty() ? "The browser reported an error without a message." : line;
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
