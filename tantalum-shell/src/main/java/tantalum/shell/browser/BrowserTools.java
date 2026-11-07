package tantalum.shell.browser;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.AriaSnapshotMode;
import com.microsoft.playwright.options.WaitForSelectorState;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import tantalum.shell.attachment.ToolImages;
import tantalum.shell.json.JsonText;
import tantalum.shell.net.AddressRules;
import tantalum.shell.net.WebUrl;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.ToolOutput;
import tantalum.value.json.Json;

/**
 * W7 的 11 个浏览器工具（设计“{@code BrowserTools.all}”）。名字和 {@code @playwright/mcp} 一样，模型熟悉这套名字。
 * 不提供上传文件、下载文件、拖放文件、执行任意 Playwright 代码（同 8091 临时浏览器的 deny 列表）。
 * 操作之后返回新的快照（同 {@code @playwright/mcp} 默认的 full 快照），少一轮“操作完再读一次”。
 */
public final class BrowserTools {

    private static final Pattern REF = Pattern.compile("(f\\d+)?e\\d+");
    private static final String REFS = " Refs come from the latest browser_snapshot or action result.";
    private static final double WAIT_MS = 30_000;
    private static final double SETTLE_MS = 250;

    private BrowserTools() {}

    public static List<Tool> all(Browsers browsers) {
        long idle = Math.max(1, browsers.settings().idleClose().toMinutes());
        BrowserTool.Check none = arguments -> { };
        return List.of(
                new BrowserTool("browser_navigate", "Open a URL in your own headless browser on the server. The browser is yours alone: "
                        + "tabs, cookies and logins are not shared with other users, and they are discarded when the browser closes after "
                        + idle + " minutes without use. Returns the page's accessibility snapshot, where elements carry refs like [ref=e5] "
                        + "for browser_click, browser_type and browser_select_option. To search the web, open "
                        + "`https://www.bing.com/search?q=<words>` (URL-encode the words). Google often shows headless browsers a "
                        + "rate-limit page instead of results. Use web_fetch instead when you only need the text of a known page: it is "
                        + "faster and uses fewer tokens.",
                        object(List.of("url"), "url", string("The http or https URL to open.", WebUrl.MAX_LENGTH)), browsers,
                        arguments -> precheck(url(arguments), browsers), call -> {
                            Page page = call.browser().page(call.session());
                            page.navigate(url(call.arguments()).toString());
                            return snapshot(call);
                        }),
                new BrowserTool("browser_navigate_back", "Go back to the previous page in the current tab. Returns the page snapshot.",
                        object(List.of()), browsers, none, call -> {
                            call.browser().page(call.session()).goBack();
                            return snapshot(call);
                        }),
                new BrowserTool("browser_snapshot", "Read the current tab: its title, URL and accessibility snapshot with element refs. "
                        + "Use it to see the page again, for example after waiting.", object(List.of()), browsers, none,
                        BrowserTools::snapshot),
                new BrowserTool("browser_click", "Click an element by its ref, like e5." + REFS + " Returns the page snapshot.",
                        object(List.of("ref"), "ref", plain("The element ref, like e5.")), browsers, none, call -> {
                            element(call).click();
                            settle(call);
                            return snapshot(call);
                        }),
                new BrowserTool("browser_type", "Replace the text of an input or text area by its ref, optionally pressing Enter "
                        + "afterwards to submit." + REFS + " Returns the page snapshot.",
                        object(List.of("ref", "text"), "ref", plain("The element ref, like e5."), "text", plain("The text to type."),
                                "submit", bool("Press Enter after typing.")), browsers, none, call -> {
                            Locator element = element(call);
                            element.fill(required(call.arguments(), "text"));
                            if (flag(call.arguments(), "submit")) {
                                element.press("Enter");
                                settle(call);
                            }
                            return snapshot(call);
                        }),
                new BrowserTool("browser_press_key", "Press a key in the current tab, like Enter, Escape, ArrowDown or Control+A. "
                        + "Returns the page snapshot.", object(List.of("key"), "key", plain("The key, in Playwright key names.")), browsers, none,
                        call -> {
                            call.browser().page(call.session()).keyboard().press(required(call.arguments(), "key"));
                            settle(call);
                            return snapshot(call);
                        }),
                new BrowserTool("browser_select_option", "Choose one or more options of a drop-down list by its ref." + REFS
                        + " Returns the page snapshot.", object(List.of("ref", "values"), "ref", plain("The element ref, like e5."), "values",
                        strings("The values or labels of the options to choose.")), browsers, none, call -> {
                            element(call).selectOption(values(call.arguments()).toArray(String[]::new));
                            return snapshot(call);
                        }),
                new BrowserTool("browser_wait_for", "Wait until a text appears, a text disappears, or a number of seconds has passed "
                        + "(at most 30). Returns the page snapshot.", waitSchema(), browsers, BrowserTools::checkWait,
                        BrowserTools::waitFor),
                new BrowserTool("browser_take_screenshot", "Take a PNG screenshot of the current tab, the visible part or the full page. "
                        + "The image is saved in the user's attachments and shown to you.",
                        object(List.of(), "fullPage", bool("Capture the whole scrollable page instead of the visible part.")), browsers,
                        none, BrowserTools::screenshot),
                new BrowserTool("browser_tabs", "List the tabs of your browser, or select one to make it the current tab of this "
                        + "conversation. Pages opened by links in a new window show up here.", tabsSchema(), browsers,
                        BrowserTools::checkTabs, BrowserTools::tabs),
                new BrowserTool("browser_evaluate", "Run a JavaScript expression in the current tab to read something from the page, "
                        + "like `document.querySelector('h1').innerText`. The result comes back as JSON, at most 50,000 characters.",
                        object(List.of("expression"), "expression", plain("A JavaScript expression or a function like () => document.title.")),
                        browsers, none, call -> {
                            Object value = call.browser().page(call.session()).evaluate(required(call.arguments(), "expression"));
                            return ToolOutput.ok(PageText.evaluated(json(value)));
                        }));
    }

    /** 快照类结果，取本会话的当前页。 */
    private static ToolOutput snapshot(BrowserTool.Call call) {
        UserBrowser browser = call.browser();
        Page page = browser.page(call.session());
        String aria = page.ariaSnapshot(new Page.AriaSnapshotOptions().setMode(AriaSnapshotMode.AI));
        List<Page> pages = browser.pages();
        return ToolOutput.ok(PageText.snapshot(page.title(), page.url(), index(pages, page), pages.size(), aria));
    }

    /**
     * 点击、回车之后等一下：网页开的新窗口、开始的跳转要等 Playwright 下一次和驱动往返时才知道（事件只在调用时处理）。
     * 先等 250 ms，再等页面的 DOM 加载完，最多 5 秒；等不到也照常返回快照。
     */
    private static void settle(BrowserTool.Call call) {
        Page page = call.browser().page(call.session());
        page.waitForTimeout(SETTLE_MS);
        try {
            page.waitForLoadState(LoadState.DOMCONTENTLOADED, new Page.WaitForLoadStateOptions().setTimeout(UserBrowser.ACTION_MS));
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
    private static Locator element(BrowserTool.Call call) {
        String ref = required(call.arguments(), "ref");
        String message = "Element ref " + ref + " is not on the current page; call browser_snapshot for fresh refs.";
        if (!REF.matcher(ref).matches()) {
            throw new IllegalArgumentException(message);
        }
        Locator element = call.browser().page(call.session()).getByRef(ref);
        if (element.count() == 0) {
            throw new IllegalArgumentException(message);
        }
        return element;
    }

    private static URI url(Json arguments) {
        return WebUrl.check(required(arguments, "url"));
    }

    /**
     * 网址里的主机本身被拦时，事先就给 {@link AddressRules#describe} 那句清楚的话；页面里的子请求和跳转由出口兜底。
     * 解析不了时不报错：有上游代理时由代理解析，出口会兜底。
     */
    private static void precheck(URI url, Browsers browsers) {
        String host = url.getHost();
        int port = url.getPort() >= 0 ? url.getPort() : url.getScheme().equalsIgnoreCase("https") ? 443 : 80;
        List<InetAddress> addresses;
        try {
            addresses = List.of(InetAddress.getAllByName(host));
        } catch (UnknownHostException unresolved) {
            return;
        }
        Optional<AddressRules.Blocked> blocked = browsers.egress().rules().firstBlocked(addresses, port);
        if (blocked.isPresent()) {
            throw new IllegalArgumentException(AddressRules.describe(host, port, blocked.get().address(), blocked.get().reason()));
        }
    }

    private static void checkWait(Json arguments) {
        Optional<Double> seconds = number(arguments, "seconds");
        if (optional(arguments, "text").isEmpty() && optional(arguments, "textGone").isEmpty() && seconds.isEmpty()) {
            throw new IllegalArgumentException("Give text, textGone or seconds.");
        }
        if (seconds.isPresent() && (seconds.get() < 0 || seconds.get() > 30)) {
            throw new IllegalArgumentException("seconds must be between 0 and 30.");
        }
    }

    private static ToolOutput waitFor(BrowserTool.Call call) {
        Page page = call.browser().page(call.session());
        Json arguments = call.arguments();
        Optional<Double> seconds = number(arguments, "seconds");
        if (seconds.isPresent()) {
            page.waitForTimeout(seconds.get() * 1000);
        }
        Optional<String> text = optional(arguments, "text");
        if (text.isPresent()) {
            page.getByText(text.get()).first().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.VISIBLE).setTimeout(WAIT_MS));
        }
        Optional<String> gone = optional(arguments, "textGone");
        if (gone.isPresent()) {
            page.getByText(gone.get()).first().waitFor(new Locator.WaitForOptions()
                    .setState(WaitForSelectorState.HIDDEN).setTimeout(WAIT_MS));
        }
        return snapshot(call);
    }

    /** 截图经 {@link ToolImages} 存进本人附件，名字 {@code screenshot-<主机>.png}；结果是 W9 那句话加图片说明行。 */
    private static ToolOutput screenshot(BrowserTool.Call call) throws Exception {
        Page page = call.browser().page(call.session());
        byte[] png = page.screenshot(new Page.ScreenshotOptions().setFullPage(flag(call.arguments(), "fullPage")));
        String url = page.url();
        String host;
        try {
            host = URI.create(url).getHost();
        } catch (IllegalArgumentException odd) {
            host = null;
        }
        String name = "screenshot-" + (host == null || host.isEmpty() ? "page" : host) + ".png";
        return call.browsers().images().output(call.user(), PageText.screenshot(url), false,
                List.of(new ToolImages.Image("image/png", png)), name);
    }

    private static void checkTabs(Json arguments) {
        String action = required(arguments, "action");
        if (!action.equals("list") && !action.equals("select")) {
            throw new IllegalArgumentException("action must be list or select.");
        }
        if (action.equals("select") && number(arguments, "index").isEmpty()) {
            throw new IllegalArgumentException("Give the index of the tab to select (1 is the first tab).");
        }
    }

    private static ToolOutput tabs(BrowserTool.Call call) {
        UserBrowser browser = call.browser();
        Page current = browser.page(call.session());
        List<Page> pages = browser.pages();
        if (required(call.arguments(), "action").equals("select")) {
            int index = number(call.arguments(), "index").orElseThrow().intValue();
            if (index < 1 || index > pages.size()) {
                throw new IllegalArgumentException("There is no tab " + index + "; this browser has " + pages.size() + " tabs.");
            }
            browser.select(call.session(), pages.get(index - 1));
            return snapshot(call);
        }
        List<PageText.Tab> tabs = new ArrayList<>();
        for (Page page : pages) {
            tabs.add(new PageText.Tab(page.title(), page.url()));
        }
        return ToolOutput.ok(PageText.tabs(tabs, index(pages, current)));
    }

    private static String json(Object value) {
        try {
            return JsonText.toText(JsonText.fromPlain(value));
        } catch (RuntimeException unusual) {
            return JsonText.quote(String.valueOf(value));
        }
    }

    // ---- 参数 ----

    private static String required(Json arguments, String name) {
        return optional(arguments, name).orElseThrow(() -> new IllegalArgumentException("Missing argument: " + name));
    }

    private static Optional<String> optional(Json arguments, String name) {
        return arguments instanceof Json.Obj object && object.fields().get(name) instanceof Json.Str value
                ? Optional.of(value.value()) : Optional.empty();
    }

    private static boolean flag(Json arguments, String name) {
        return arguments instanceof Json.Obj object && object.fields().get(name) instanceof Json.Bool value && value.value();
    }

    private static Optional<Double> number(Json arguments, String name) {
        return arguments instanceof Json.Obj object && object.fields().get(name) instanceof Json.Num value
                ? Optional.of(value.value().doubleValue()) : Optional.empty();
    }

    private static List<String> values(Json arguments) {
        if (arguments instanceof Json.Obj object && object.fields().get("values") instanceof Json.Arr array) {
            List<String> values = new ArrayList<>();
            for (Json item : array.items()) {
                if (item instanceof Json.Str value) {
                    values.add(value.value());
                }
            }
            if (!values.isEmpty()) {
                return values;
            }
        }
        throw new IllegalArgumentException("Missing argument: values");
    }

    // ---- 参数 schema：键按写下的顺序（Map.of 的顺序每次启动都不同，工具定义一变，提示缓存就失效）----

    /** 按顺序的 JSON 对象：键、值、键、值…… */
    private static Json obj(Object... pairs) {
        Map<String, Json> fields = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            Object value = pairs[i + 1];
            fields.put((String) pairs[i], value instanceof Json json ? json : value instanceof Integer n
                    ? new Json.Num(BigDecimal.valueOf(n)) : new Json.Str((String) value));
        }
        return new Json.Obj(fields);
    }

    /** 参数对象：{@code properties} 是按顺序的“名字、schema”对，{@code required} 是必填的名字。 */
    private static Json object(List<String> required, Object... properties) {
        Json schema = obj("type", "object", "properties", obj(properties));
        if (required.isEmpty()) {
            return schema;
        }
        Map<String, Json> fields = new LinkedHashMap<>(((Json.Obj) schema).fields());
        fields.put("required", new Json.Arr(required.stream().<Json>map(Json.Str::new).toList()));
        return new Json.Obj(fields);
    }

    private static Json plain(String description) {
        return obj("type", "string", "description", description);
    }

    private static Json string(String description, int maxLength) {
        return obj("type", "string", "description", description, "maxLength", maxLength);
    }

    private static Json bool(String description) {
        return obj("type", "boolean", "description", description);
    }

    private static Json strings(String description) {
        return obj("type", "array", "items", obj("type", "string"), "minItems", 1, "description", description);
    }

    private static Json waitSchema() {
        return object(List.of(), "text", plain("Wait until this text is visible."),
                "textGone", plain("Wait until this text is no longer visible."),
                "seconds", obj("type", "number", "description", "Wait this many seconds.", "minimum", 0, "maximum", 30));
    }

    private static Json tabsSchema() {
        return object(List.of("action"),
                "action", obj("type", "string", "enum", new Json.Arr(List.of(new Json.Str("list"), new Json.Str("select"))),
                        "description", "list the tabs, or select one by index."),
                "index", obj("type", "integer", "minimum", 1, "description", "The tab to select, 1 is the first tab."));
    }
}
