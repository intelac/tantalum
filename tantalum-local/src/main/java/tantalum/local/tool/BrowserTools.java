package tantalum.local.tool;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tantalum.local.browser.BrowserArguments;
import tantalum.local.browser.PageUrl;
import tantalum.value.json.Json;

/**
 * 11 个浏览器工具的定义（web-tools W7，local-browser 需求 4）。名字和 {@code @playwright/mcp} 一样，模型熟悉这套名字；
 * 名字和参数同浏览器还在服务器上的时候，旧会话里的调用对得上。不提供上传文件、下载文件、拖放文件、执行任意 Playwright 代码。
 * 定义是固定的：不随电脑、浏览器种类、有没有窗口而变，提示缓存才不失效。
 */
public final class BrowserTools {

    /** QAgent 问题 71：批准卡片上只有 e5 这种 ref，用户看不懂要点的是什么；照 Playwright MCP 的 element 参数。 */
    private static final String ELEMENT = "What the element is, in a few words, like \"Submit button\"; shown to the user when they are asked to "
            + "approve this action.";
    private static final String REFS = " Refs come from the latest browser_snapshot or action result.";

    private static final List<LocalTool> ALL = build();

    private BrowserTools() {}

    public static List<LocalTool> all() {
        return ALL;
    }

    private static List<LocalTool> build() {
        BrowserTool.Check none = arguments -> { };
        BrowserTool.Check ref = BrowserArguments::ref;
        return List.of(
                new BrowserTool("browser_navigate", "Open a URL in a browser on the user's computer. The browser has its own profile, "
                        + "separate from the user's everyday browser; logins made in it are kept between conversations. Its window may be "
                        + "visible to the user, who may also act in it, for example to log in when you ask. Returns the page's "
                        + "accessibility snapshot, where elements carry refs like [ref=e5] "
                        + "for browser_click, browser_type and browser_select_option. To search the web, open "
                        + "`https://www.bing.com/search?q=<words>` (URL-encode the words). Google often shows automated browsers a "
                        + "rate-limit page instead of results. If a web_fetch tool is available, prefer it when you only need the text of a "
                        + "known public page: it is faster and uses fewer tokens.",
                        object(List.of("url"), "url", string("The http or https URL to open.", PageUrl.MAX_LENGTH)),
                        arguments -> PageUrl.check(BrowserArguments.required(arguments, "url"))),
                new BrowserTool("browser_navigate_back", "Go back to the previous page in the current tab. Returns the page snapshot.",
                        object(List.of()), none),
                new BrowserTool("browser_snapshot", "Read the current tab: its title, URL and accessibility snapshot with element refs. "
                        + "Use it to see the page again, for example after waiting.", object(List.of()), none),
                new BrowserTool("browser_click", "Click an element by its ref, like e5." + REFS + " Returns the page snapshot.",
                        object(List.of("ref"), "ref", plain("The element ref, like e5."), "element", plain(ELEMENT)), ref),
                new BrowserTool("browser_type", "Replace the text of an input or text area by its ref, optionally pressing Enter "
                        + "afterwards to submit." + REFS + " Returns the page snapshot.",
                        object(List.of("ref", "text"), "ref", plain("The element ref, like e5."), "element", plain(ELEMENT), "text", plain("The text to type."),
                                "submit", bool("Press Enter after typing.")),
                        arguments -> {
                            BrowserArguments.ref(arguments);
                            BrowserArguments.required(arguments, "text");
                        }),
                new BrowserTool("browser_press_key", "Press a key in the current tab, like Enter, Escape, ArrowDown or Control+A. "
                        + "Returns the page snapshot.", object(List.of("key"), "key", plain("The key, in Playwright key names.")),
                        arguments -> BrowserArguments.required(arguments, "key")),
                new BrowserTool("browser_select_option", "Choose one or more options of a drop-down list by its ref." + REFS
                        + " Returns the page snapshot.", object(List.of("ref", "values"), "ref", plain("The element ref, like e5."), "element", plain(ELEMENT), "values",
                        strings("The values or labels of the options to choose.")),
                        arguments -> {
                            BrowserArguments.ref(arguments);
                            BrowserArguments.values(arguments);
                        }),
                new BrowserTool("browser_wait_for", "Wait until a text appears, a text disappears, or a number of seconds has passed "
                        + "(at most 30). Returns the page snapshot.", waitSchema(), BrowserArguments::checkWait),
                new BrowserTool("browser_take_screenshot", "Take a PNG screenshot of the current tab, the visible part or the full page. "
                        + "The image is saved in the user's attachments and shown to you.",
                        object(List.of(), "fullPage", bool("Capture the whole scrollable page instead of the visible part.")), none),
                new BrowserTool("browser_tabs", "List the tabs of your browser, or select one to make it the current tab of this "
                        + "conversation. Pages opened by links in a new window show up here.", tabsSchema(), BrowserArguments::checkTabs),
                new BrowserTool("browser_evaluate", "Run a JavaScript expression in the current tab to read something from the page, "
                        + "like `document.querySelector('h1').innerText`. The result comes back as JSON, at most 50,000 characters.",
                        object(List.of("expression"), "expression", plain("A JavaScript expression or a function like () => document.title.")),
                        arguments -> BrowserArguments.required(arguments, "expression")));
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
