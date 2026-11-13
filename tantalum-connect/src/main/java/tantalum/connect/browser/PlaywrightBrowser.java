package tantalum.connect.browser;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tantalum.local.browser.BrowserArguments;
import tantalum.local.browser.PageText;
import tantalum.local.tool.Browser;
import tantalum.local.tool.LocalImage;
import tantalum.local.tool.LocalResult;
import tantalum.value.json.Json;

/**
 * 这台电脑上的浏览器，由本机的 {@code @playwright/mcp} 驱动（connector-jar 需求 7）。服务器上 11 个 {@code browser_*} 工具的定义不变，
 * 这里把调用翻译成 Playwright MCP 的，再把结果整理成原来的样子（{@link PageText}），模型看到的和以前一样：
 * <ul>
 *   <li>{@code ref} 换成 {@code target}；{@code browser_tabs} 的编号从 1 起换成从 0 起；{@code browser_wait_for} 的
 *       {@code seconds} 换成 {@code time}；{@code browser_evaluate} 的 {@code expression} 原样当 {@code function} 给
 *       （Playwright 的 {@code page.evaluate} 认表达式也认函数）。</li>
 *   <li>会改页面的工具调完再取一次快照和标签页列表，拼成“页面、网址、第几个标签页、快照”：Playwright MCP 0.0.85 调完
 *       {@code browser_navigate} 这类只给一个快照文件的路径（写在 {@code --output-dir} 里），模型读不到。</li>
 *   <li>截图只取图片，文字是那句“不可信”加网址。</li>
 * </ul>
 * 标签页是这台电脑上所有会话共用的（Playwright MCP 一个进程一个浏览器）；以前每个会话记着自己的当前页，现在不分。
 */
public final class PlaywrightBrowser implements Browser {

    /** 本机 MCP 里浏览器那一项的名字；{@code mcp.json} 里有同名的就用那个。 */
    public static final String SERVER = "playwright";
    /** 内置的 Playwright MCP 版本：翻译按它的参数和输出写，升级时一起测（{@code PlaywrightMcpIT}）。 */
    public static final String PACKAGE = "@playwright/mcp@0.0.85";

    private static final Pattern URL = Pattern.compile("^- Page URL: (.*)$", Pattern.MULTILINE);
    private static final Pattern TITLE = Pattern.compile("^- Page Title: (.*)$", Pattern.MULTILINE);
    private static final Pattern YAML = Pattern.compile("### Snapshot\\n```yaml\\n(.*?)\\n```", Pattern.DOTALL);
    private static final Pattern TAB = Pattern.compile("^- (\\d+): (\\(current\\) )?\\[(.*)\\]\\((.*)\\)$", Pattern.MULTILINE);
    private static final Pattern RESULT = Pattern.compile("### Result\\n(.*?)(?:\\n### |\\z)", Pattern.DOTALL);
    private static final Pattern ERROR = Pattern.compile("### Error\\n(?:Error: )?(.*?)(?:\\n### |\\z)", Pattern.DOTALL);
    private static final Pattern MISSING_REF = Pattern.compile("Ref (\\S+) not found");

    /** 调一个本机 MCP 工具：{@code tool} 是 Playwright MCP 自己的工具名。 */
    @FunctionalInterface
    public interface Mcp {
        LocalResult call(String tool, Json arguments) throws InterruptedException;
    }

    private final Mcp mcp;
    /** Playwright MCP 写快照、截图文件的目录（{@code --output-dir}）；每次调用后清空，不让它只增不删（QAgent 问题 26）。可以为 null。 */
    private final java.nio.file.Path output;

    public PlaywrightBrowser(Mcp mcp) {
        this(mcp, null);
    }

    public PlaywrightBrowser(Mcp mcp, java.nio.file.Path output) {
        this.mcp = mcp;
        this.output = output;
        clean();
    }

    /** 清空 {@link #output} 里的文件：快照我们另取、截图随结果送回，这些文件之后用不到。删不掉的（正被写）留到下次。 */
    void clean() {
        if (output == null || !java.nio.file.Files.isDirectory(output)) {
            return;
        }
        try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(output)) {
            for (java.nio.file.Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                if (!file.equals(output)) {
                    try {
                        java.nio.file.Files.deleteIfExists(file);
                    } catch (java.io.IOException busy) {
                        // 下次再删
                    }
                }
            }
        } catch (java.io.IOException | java.io.UncheckedIOException unreadable) {
            // 下次再删
        }
    }

    /** 内置的那一项：用电脑上的 {@code npx} 跑固定版本的 Playwright MCP。 */
    public static List<String> args(String browser, Path executable, Path profile, Path output, boolean headless) {
        List<String> args = new ArrayList<>(List.of("-y", PACKAGE, "--browser", browser, "--user-data-dir", profile.toString(),
                "--output-dir", output.toString(), "--image-responses", "allow"));
        if (executable != null) {
            args.addAll(List.of("--executable-path", executable.toString()));
        }
        if (headless) {
            args.add("--headless");
        }
        return args;
    }

    @Override
    public LocalResult call(String session, String tool, Json arguments) throws Exception {
        try {
            return translate(tool, arguments);
        } finally {
            clean();
        }
    }

    private LocalResult translate(String tool, Json arguments) throws Exception {
        switch (tool) {
            case "browser_navigate" -> {
                String url = BrowserArguments.required(arguments, "url");
                LocalResult done = run("browser_navigate", Map.of("url", new Json.Str(url)));
                return done.error() ? done : page(url);
            }
            case "browser_navigate_back" -> {
                return then(run("browser_navigate_back", Map.of()));
            }
            case "browser_snapshot" -> {
                return page(null);
            }
            case "browser_click" -> {
                return then(run("browser_click", target(arguments)));
            }
            case "browser_type" -> {
                Map<String, Json> args = target(arguments);
                args.put("text", new Json.Str(BrowserArguments.required(arguments, "text")));
                if (BrowserArguments.flag(arguments, "submit")) {
                    args.put("submit", new Json.Bool(true));
                }
                return then(run("browser_type", args));
            }
            case "browser_press_key" -> {
                return then(run("browser_press_key", Map.of("key", new Json.Str(BrowserArguments.required(arguments, "key")))));
            }
            case "browser_select_option" -> {
                Map<String, Json> args = target(arguments);
                args.put("values", new Json.Arr(BrowserArguments.values(arguments).stream().<Json>map(Json.Str::new).toList()));
                return then(run("browser_select_option", args));
            }
            case "browser_wait_for" -> {
                BrowserArguments.checkWait(arguments);
                Map<String, Json> args = new LinkedHashMap<>();
                BrowserArguments.number(arguments, "seconds").ifPresent(seconds -> args.put("time", number(seconds)));
                BrowserArguments.optional(arguments, "text").ifPresent(text -> args.put("text", new Json.Str(text)));
                BrowserArguments.optional(arguments, "textGone").ifPresent(text -> args.put("textGone", new Json.Str(text)));
                return then(run("browser_wait_for", args));
            }
            case "browser_take_screenshot" -> {
                return screenshot(arguments);
            }
            case "browser_tabs" -> {
                return tabs(arguments);
            }
            case "browser_evaluate" -> {
                LocalResult done = run("browser_evaluate",
                        Map.of("function", new Json.Str(BrowserArguments.required(arguments, "expression"))));
                return done.error() ? done : LocalResult.ok(PageText.evaluated(compact(section(RESULT, done.text()).orElse("undefined"))));
            }
            default -> throw new IllegalArgumentException("Unknown tool: " + tool);
        }
    }

    /** 调 Playwright MCP；出错时只留错误那一段，元素编号找不到时说法同以前。 */
    private LocalResult run(String tool, Map<String, Json> arguments) throws InterruptedException {
        LocalResult result = mcp.call(tool, new Json.Obj(new LinkedHashMap<>(arguments)));
        return result.error() ? LocalResult.error(error(result.text())) : result;
    }

    static String error(String text) {
        String message = section(ERROR, text).orElse(text).strip();
        Matcher missing = MISSING_REF.matcher(message);
        return missing.find() ? BrowserArguments.staleRef(missing.group(1)) : message;
    }

    private LocalResult then(LocalResult done) throws InterruptedException {
        return done.error() ? done : page(null);
    }

    /** 当前页的快照，拼成以前的样子；{@code requested} 是 {@code browser_navigate} 要打开的网址。 */
    private LocalResult page(String requested) throws InterruptedException {
        LocalResult snapshot = run("browser_snapshot", Map.of());
        if (snapshot.error()) {
            return snapshot;
        }
        LocalResult listed = run("browser_tabs", Map.of("action", new Json.Str("list")));
        List<Tab> tabs = listed.error() ? List.of() : tabs(listed.text());
        int current = current(tabs);
        return LocalResult.ok(snapshot(snapshot.text(), current + 1, Math.max(tabs.size(), 1), requested));
    }

    /** Playwright MCP 的快照结果转成 {@link PageText#snapshot}。 */
    static String snapshot(String text, int tab, int tabs, String requested) {
        String url = first(URL, text).orElse("about:blank");
        String title = first(TITLE, text).orElse("");
        String yaml = first(YAML, text).orElse("");
        return PageText.snapshot(title, url, tab, tabs, yaml, requested);
    }

    private LocalResult screenshot(Json arguments) throws InterruptedException {
        Map<String, Json> args = new LinkedHashMap<>();
        args.put("type", new Json.Str("png"));
        if (BrowserArguments.flag(arguments, "fullPage")) {
            args.put("fullPage", new Json.Bool(true));
        }
        LocalResult shot = run("browser_take_screenshot", args);
        if (shot.error()) {
            return shot;
        }
        if (shot.images().isEmpty()) {
            return LocalResult.error("The browser returned no screenshot.");
        }
        LocalResult listed = run("browser_tabs", Map.of("action", new Json.Str("list")));
        List<Tab> tabs = listed.error() ? List.of() : tabs(listed.text());
        String url = tabs.isEmpty() ? "about:blank" : tabs.get(current(tabs)).url();
        String host;
        try {
            host = URI.create(url).getHost();
        } catch (IllegalArgumentException odd) {
            host = null;
        }
        String name = "screenshot-" + (host == null || host.isEmpty() ? "page" : host) + ".png";
        return LocalResult.image(PageText.screenshot(url), new LocalImage("image/png", shot.images().getFirst().bytes(), name));
    }

    private LocalResult tabs(Json arguments) throws InterruptedException {
        BrowserArguments.checkTabs(arguments);
        if (BrowserArguments.required(arguments, "action").equals("select")) {
            int index = BrowserArguments.number(arguments, "index").orElseThrow().intValue();
            LocalResult listed = run("browser_tabs", Map.of("action", new Json.Str("list")));
            int count = listed.error() ? 0 : tabs(listed.text()).size();
            if (index < 1 || index > count) {
                return LocalResult.error("There is no tab " + index + "; this browser has " + count + " tabs.");
            }
            Map<String, Json> select = new LinkedHashMap<>();
            select.put("action", new Json.Str("select"));
            select.put("index", number(index - 1.0));
            return then(run("browser_tabs", select));
        }
        LocalResult listed = run("browser_tabs", Map.of("action", new Json.Str("list")));
        if (listed.error()) {
            return listed;
        }
        List<Tab> tabs = tabs(listed.text());
        List<PageText.Tab> shown = tabs.stream().map(tab -> new PageText.Tab(tab.title(), tab.url())).toList();
        return LocalResult.ok(PageText.tabs(shown, current(tabs) + 1));
    }

    /** 标签页列表里的一行：编号（从 0 起）、是不是当前的、标题、网址。 */
    record Tab(int index, boolean current, String title, String url) {}

    static List<Tab> tabs(String text) {
        String list = section(RESULT, text).orElse(text);
        List<Tab> tabs = new ArrayList<>();
        Matcher matcher = TAB.matcher(list);
        while (matcher.find()) {
            tabs.add(new Tab(Integer.parseInt(matcher.group(1)), matcher.group(2) != null, matcher.group(3), matcher.group(4)));
        }
        return tabs;
    }

    private static int current(List<Tab> tabs) {
        for (int i = 0; i < tabs.size(); i++) {
            if (tabs.get(i).current()) {
                return i;
            }
        }
        return 0;
    }

    /** Playwright MCP 把返回值排成多行的 JSON：压回一行，同以前，也省 token。不是 JSON（{@code undefined}）就原样。 */
    static String compact(String json) {
        try {
            return tantalum.local.wire.Wire.text(tantalum.local.wire.Wire.json(json));
        } catch (IllegalArgumentException notJson) {
            return json;
        }
    }

    private static Map<String, Json> target(Json arguments) {
        String ref = BrowserArguments.ref(arguments);
        Map<String, Json> args = new LinkedHashMap<>();
        args.put("target", new Json.Str(ref));
        // 模型给了元素说明（QAgent 问题 71）就照转，没给用 ref
        String element = arguments instanceof Json.Obj object && object.fields().get("element") instanceof Json.Str text
                && !text.value().isBlank() ? text.value().strip() : ref;
        args.put("element", new Json.Str(element));
        return args;
    }

    private static Json number(double value) {
        return new Json.Num(java.math.BigDecimal.valueOf(value).stripTrailingZeros());
    }

    private static Optional<String> first(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? Optional.of(matcher.group(1).strip()) : Optional.empty();
    }

    private static Optional<String> section(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? Optional.of(matcher.group(1).strip()) : Optional.empty();
    }
}
