package tantalum.connect.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tantalum.local.browser.PageText;
import tantalum.local.fs.AllowedDirs;
import tantalum.local.tool.BrowserTools;
import tantalum.local.tool.LocalContext;
import tantalum.local.tool.LocalImage;
import tantalum.local.tool.LocalResult;
import tantalum.local.tool.LocalTool;
import tantalum.local.tool.Observations;
import tantalum.local.sandbox.Sandbox;
import tantalum.local.sandbox.Unavailable;
import tantalum.value.json.Json;

/**
 * local-browser 需求 10.1、10.2：真的 Chrome 或 Edge（这台机器上装的那个）、无头、本机测试站，不访问外网。
 * 走的是工具的定义加 {@link LocalBrowser}，和连接程序收到一个调用后走的路一样。没有装 Chrome、Edge 时跳过并写明原因。
 * 每个用例结束时没有驱动和浏览器的子进程；资料目录用临时目录，不碰这台机器上连接程序真的资料目录。
 */
@Timeout(240)
class LocalBrowserIT {

    private static final Pattern REF = Pattern.compile("\"([^\"\\n]+)\"[^\\n]*\\[ref=((?:f\\d+)?e\\d+)]");
    /** 驱动只解压一次，几个用例和几次构建共用；不放在真的家目录里。 */
    private static final Path DRIVER_HOME = Path.of("target", "it-browser-driver").toAbsolutePath();

    @TempDir
    Path temp;

    private final List<LocalBrowser> browsers = new ArrayList<>();
    private Optional<Installed.Found> installed;
    private HttpServer site;
    private String base;
    private String download;

    @BeforeEach
    void start() throws IOException {
        installed = Installed.find(Installed.Choice.ANY);
        assumeTrue(installed.isPresent(), "neither Google Chrome nor Microsoft Edge is installed on this machine");
        download = "tantalum-it-" + UUID.randomUUID() + ".bin";
        site = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        page("/", "<html><head><title>Form</title></head><body><h1>Hello</h1>"
                + "<input aria-label='Query' id='q'><button onclick=\"document.title='clicked ' + q.value\">Go</button>"
                + "<select aria-label='Color'><option value='r'>Red</option><option value='g'>Green</option></select>"
                + "<a href='/next'>Next page</a><a href='/next' target='_blank'>New tab</a>"
                + "<a href='/file.bin'>Get file</a>"
                + "<script>setTimeout(() => document.body.insertAdjacentHTML('beforeend', '<p>Arrived later</p>'), 300)</script>"
                + "</body></html>");
        page("/next", "<html><head><title>Next</title></head><body><p>second page</p></body></html>");
        site.createContext("/login", exchange -> {
            exchange.getResponseHeaders().add("Set-Cookie", "who=yi; Max-Age=3600; Path=/");
            respond(exchange, "<html><head><title>Logged in</title></head><body><p>welcome</p></body></html>");
        });
        site.createContext("/whoami", exchange -> {
            String cookie = exchange.getRequestHeaders().getFirst("Cookie");
            respond(exchange, "<html><head><title>Who</title></head><body><p>cookie=" + (cookie == null ? "none" : cookie)
                    + "</p></body></html>");
        });
        site.createContext("/file.bin", exchange -> {
            exchange.getResponseHeaders().add("Content-Disposition", "attachment; filename=" + download);
            exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, 3);
            exchange.getResponseBody().write(new byte[] {1, 2, 3});
            exchange.close();
        });
        site.createContext("/n/", exchange -> respond(exchange, "<html><head><title>N" + exchange.getRequestURI().getPath().substring(3)
                + "</title></head><body><p>numbered</p></body></html>"));
        site.start();
        base = "http://127.0.0.1:" + site.getAddress().getPort();
    }

    private void page(String path, String html) {
        site.createContext(path, exchange -> respond(exchange, html));
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String html) throws IOException {
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    @AfterEach
    void stop() throws Exception {
        for (LocalBrowser browser : browsers) {
            browser.close();
        }
        if (site != null) {
            site.stop(0);
        }
        if (installed != null && installed.isPresent()) {
            awaitNoBrowserProcesses();
        }
    }

    /** 一个无头的浏览器，资料目录在临时目录下的 {@code name}。 */
    private LocalBrowser browser(String profile) throws IOException {
        Installed.Found found = installed.orElseThrow();
        LocalBrowser browser = new LocalBrowser(new LocalBrowser.Settings(found.path(), found.name(), true,
                Profile.prepare(temp.resolve(profile))), new Driver(DRIVER_HOME), Clock.systemUTC());
        browsers.add(browser);
        return browser;
    }

    private LocalResult call(LocalBrowser browser, String session, String tool, Object... pairs) throws Exception {
        Map<String, Json> arguments = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            Object value = pairs[i + 1];
            arguments.put((String) pairs[i], value instanceof Json json ? json : value instanceof Boolean flag ? new Json.Bool(flag)
                    : value instanceof Integer number ? new Json.Num(java.math.BigDecimal.valueOf(number)) : new Json.Str((String) value));
        }
        LocalTool definition = BrowserTools.all().stream().filter(candidate -> candidate.name().equals(tool)).findFirst().orElseThrow();
        LocalContext context = new LocalContext(session, new AllowedDirs(List.of(temp)), new Observations(),
                Sandbox.unavailable(Unavailable.of(Unavailable.Code.SHELL_NOT_ALLOWED)), null, browser);
        return definition.run(context, new Json.Obj(arguments));
    }

    private static String ref(LocalResult snapshot, String name) {
        Matcher matcher = REF.matcher(snapshot.text());
        while (matcher.find()) {
            if (matcher.group(1).equals(name)) {
                return matcher.group(2);
            }
        }
        throw new AssertionError("no ref for " + name + " in\n" + snapshot.text());
    }

    private static boolean isBrowserProcess(ProcessHandle process) {
        String command = process.info().command().orElse("").toLowerCase(java.util.Locale.ROOT);
        return command.contains("chrome") || command.contains("edge") || command.contains("node");
    }

    private static Set<Long> browserProcesses() {
        Set<Long> pids = new HashSet<>();
        ProcessHandle.current().descendants().filter(LocalBrowserIT::isBrowserProcess).forEach(process -> pids.add(process.pid()));
        return pids;
    }

    private static void awaitNoBrowserProcesses() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (!browserProcesses().isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        assertEquals(Set.of(), browserProcesses(), "no driver or browser process left");
    }

    private static List<Path> find(Path dir, String name) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(dir, 4)) {
            return walk.filter(path -> path.getFileName() != null && path.getFileName().toString().contains(name)).toList();
        } catch (java.io.UncheckedIOException unreadable) {
            return List.of();
        }
    }

    /** 需求 4.1、4.4–4.7：11 个工具在真浏览器上各走一遍（搬自服务器的 BrowserToolsIT，断言不变）。 */
    @Test
    void theElevenToolsWorkOnARealBrowser() throws Exception {
        LocalBrowser browser = browser("p");
        assertEquals(0, browser.launches(), "nothing starts before the first call");
        LocalResult opened = call(browser, "s1", "browser_navigate", "url", base + "/");
        assertFalse(opened.error(), opened.text());
        assertEquals(PageText.UNTRUSTED, opened.text().lines().findFirst().orElseThrow());
        assertTrue(opened.text().contains("Page: Form\nURL: " + base + "/\nTab 1 of 1"), opened.text());

        // 按编号输入、点击：结果里是新的快照
        LocalResult typed = call(browser, "s1", "browser_type", "ref", ref(opened, "Query"), "text", "bing");
        assertFalse(typed.error(), typed.text());
        LocalResult clicked = call(browser, "s1", "browser_click", "ref", ref(typed, "Go"));
        assertTrue(clicked.text().contains("Page: clicked bing"), clicked.text());
        LocalResult stale = call(browser, "s1", "browser_click", "ref", "e999");
        assertEquals("Element ref e999 is not on the current page; call browser_snapshot for fresh refs.", stale.text());
        assertTrue(stale.error());

        LocalResult selected = call(browser, "s1", "browser_select_option", "ref", ref(clicked, "Color"),
                "values", new Json.Arr(List.of(new Json.Str("g"))));
        assertFalse(selected.error(), selected.text());
        LocalResult evaluated = call(browser, "s1", "browser_evaluate", "expression",
                "() => ({color: document.querySelector('select').value, n: 1 + 1})");
        assertEquals(PageText.UNTRUSTED + "\n{\"color\":\"g\",\"n\":2}", evaluated.text());

        LocalResult waited = call(browser, "s1", "browser_wait_for", "text", "Arrived later");
        assertTrue(waited.text().contains("Arrived later"), waited.text());
        assertFalse(call(browser, "s1", "browser_press_key", "key", "Tab").error());

        // 截图：一张 PNG，带建议的文件名，文字是那句话加截的是哪个网址（需求 4.5）
        LocalResult shot = call(browser, "s1", "browser_take_screenshot");
        assertFalse(shot.error(), shot.text());
        assertEquals(PageText.UNTRUSTED + "\nScreenshot of " + base + "/", shot.text());
        LocalImage image = shot.images().getFirst();
        assertEquals("image/png", image.mime());
        assertEquals("screenshot-127.0.0.1.png", image.name());
        assertEquals((byte) 0x89, image.bytes()[0]);
        assertEquals('P', image.bytes()[1]);
        assertTrue(image.bytes().length > 1000, "a real picture: " + image.bytes().length + " bytes");

        // 网页开的新窗口出现在标签页列表里，选中后成为当前页；后退
        call(browser, "s1", "browser_click", "ref", ref(call(browser, "s1", "browser_snapshot"), "New tab"));
        LocalResult tabs = call(browser, "s1", "browser_tabs", "action", "list");
        for (int i = 0; i < 50 && !tabs.text().contains("2. Next"); i++) {
            Thread.sleep(100); // 新窗口的事件随下一次调用送到
            tabs = call(browser, "s1", "browser_tabs", "action", "list");
        }
        assertTrue(tabs.text().contains("* 1. ") && tabs.text().contains("  2. Next - " + base + "/next"), tabs.text());
        LocalResult second = call(browser, "s1", "browser_tabs", "action", "select", "index", 2);
        assertTrue(second.text().contains("Page: Next") && second.text().contains("Tab 2 of 2"), second.text());
        call(browser, "s1", "browser_tabs", "action", "select", "index", 1);
        call(browser, "s1", "browser_click", "ref", ref(call(browser, "s1", "browser_snapshot"), "Next page"));
        LocalResult back = call(browser, "s1", "browser_navigate_back");
        assertTrue(back.text().contains("URL: " + base + "/\n"), back.text());
        assertEquals(1, browser.launches(), "one browser served the whole conversation");
    }

    /** 需求 5.3、5.4：几个会话共用登录状态，各有自己的当前页；标签页最多 10 个，再多时关掉最久没用的，不关当前会话的。 */
    @Test
    void sessionsShareLoginsKeepTheirOwnCurrentPageAndTabsAreCapped() throws Exception {
        LocalBrowser browser = browser("p");
        assertFalse(call(browser, "a", "browser_navigate", "url", base + "/login").error());
        LocalResult other = call(browser, "b", "browser_navigate", "url", base + "/whoami");
        assertTrue(other.text().contains("cookie=who=yi"), "the login made in session a is there for session b: " + other.text());
        assertTrue(call(browser, "a", "browser_snapshot").text().contains("Page: Logged in"), "session b did not move session a's tab");
        assertTrue(call(browser, "b", "browser_snapshot").text().contains("Page: Who"));

        for (int i = 1; i <= 11; i++) {
            assertFalse(call(browser, "n" + i, "browser_navigate", "url", base + "/n/" + i).error());
        }
        LocalResult tabs = call(browser, "n11", "browser_tabs", "action", "list");
        long open = tabs.text().lines().filter(line -> line.contains(" - " + base)).count();
        assertEquals(Tabs.MAX_TABS, open, tabs.text());
        assertTrue(tabs.text().contains("N11 - "), "the calling session's tab is kept: " + tabs.text());
        assertFalse(tabs.text().contains("Logged in"), "the tab unused for longest was closed: " + tabs.text());
        // 被关掉标签页的会话再来：开一个新的，不出错
        assertTrue(call(browser, "a", "browser_snapshot").text().contains("URL: about:blank"));
    }

    /** 需求 3.3、5.6：登录状态存在资料目录里，浏览器关掉重开后还在；换一个资料目录就没有。 */
    @Test
    void loginsSurviveARestartAndBelongToTheProfileDirectory() throws Exception {
        LocalBrowser first = browser("p");
        assertFalse(call(first, "s1", "browser_navigate", "url", base + "/login").error());
        first.close();
        awaitNoBrowserProcesses();

        LocalBrowser again = browser("p");
        LocalResult who = call(again, "s9", "browser_navigate", "url", base + "/whoami");
        assertTrue(who.text().contains("cookie=who=yi"), who.text());
        assertTrue(who.text().contains("Tab 1 of 1"), "tabs do not come back, logins do: " + who.text());
        again.close();

        LocalBrowser elsewhere = browser("another-profile");
        assertTrue(call(elsewhere, "s1", "browser_navigate", "url", base + "/whoami").text().contains("cookie=none"));
    }

    /** 需求 5.6：浏览器被关掉（这里是杀掉它的进程，等同用户退出浏览器）后，下一次调用重新打开，登录还在。 */
    @Test
    void aBrowserThatWasClosedIsOpenedAgainOnTheNextCall() throws Exception {
        LocalBrowser browser = browser("p");
        assertFalse(call(browser, "s1", "browser_navigate", "url", base + "/login").error());
        // 让 Cookie 落盘：正常关一次再开（被杀的浏览器来不及写最近几秒的 Cookie）
        browser.close();
        browsers.remove(browser);
        awaitNoBrowserProcesses();
        LocalBrowser running = browser("p");
        assertFalse(call(running, "s1", "browser_navigate", "url", base + "/next").error());
        ProcessHandle.current().descendants()
                .filter(process -> !process.info().command().orElse("").toLowerCase(java.util.Locale.ROOT).contains("node"))
                .forEach(ProcessHandle::destroyForcibly);
        LocalResult next = null;
        for (int i = 0; i < 2; i++) {
            // 正好撞上的那一次可以写明“被关了”，再下一次一定成功
            next = call(running, "s1", "browser_navigate", "url", base + "/whoami");
            if (!next.error()) {
                break;
            }
            assertEquals(LocalBrowser.CLOSED, next.text());
        }
        assertFalse(next.error(), next.text());
        assertTrue(next.text().contains("cookie=who=yi"), next.text());
        assertEquals(2, running.launches());
    }

    /** 需求 6.1：file:、chrome: 这类网址当场被拒，浏览器根本不启动。 */
    @Test
    void localFilesAndBrowserPagesAreRefusedWithoutStartingABrowser() throws Exception {
        LocalBrowser browser = browser("p");
        Path secret = Files.writeString(temp.resolve("secret.txt"), "TOP-SECRET");
        for (String url : List.of(secret.toUri().toString(), "file:///etc/hosts", "chrome://version", "view-source:" + base + "/")) {
            LocalResult refused = call(browser, "s1", "browser_navigate", "url", url);
            assertTrue(refused.error(), url);
            assertEquals("Only http and https URLs are allowed.", refused.text(), url);
            assertFalse(refused.text().contains("TOP-SECRET"));
        }
        assertEquals(0, browser.launches());
        assertEquals(Set.of(), browserProcesses());
    }

    /** 需求 4.8：下载被拒，哪里都没有留下那个文件。 */
    @Test
    void downloadsAreRefusedAndLeaveNoFile() throws Exception {
        LocalBrowser browser = browser("p");
        LocalResult form = call(browser, "s1", "browser_navigate", "url", base + "/");
        LocalResult clicked = call(browser, "s1", "browser_click", "ref", ref(form, "Get file"));
        LocalResult direct = call(browser, "s1", "browser_navigate", "url", base + "/file.bin");
        assertEquals("This URL starts a file download; downloads are not allowed.", direct.text());
        assertTrue(direct.error());
        assertFalse(clicked.text().contains(download), "the click result does not name a saved file: " + clicked.text());
        for (Path dir : List.of(Path.of(System.getProperty("java.io.tmpdir")), temp,
                Path.of(System.getProperty("user.home"), "Downloads"))) {
            assertEquals(List.of(), find(dir, download), "downloaded file in " + dir);
        }
    }

    /** 需求 3.7：同一个资料目录被另一个浏览器占着：写明原因，不动那个目录；那一个不受影响。 */
    @Test
    void aProfileUsedByAnotherBrowserIsReportedAndLeftAlone() throws Exception {
        LocalBrowser first = browser("p");
        assertFalse(call(first, "s1", "browser_navigate", "url", base + "/login").error());
        LocalBrowser second = browser("p");
        LocalResult refused = call(second, "s1", "browser_navigate", "url", base + "/whoami");
        assertTrue(refused.error());
        assertEquals(LocalBrowser.IN_USE, refused.text());
        assertTrue(call(first, "s1", "browser_navigate", "url", base + "/whoami").text().contains("cookie=who=yi"));
    }
}
