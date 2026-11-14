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
import tantalum.local.shell.Shell;
import tantalum.local.shell.Unavailable;
import tantalum.value.json.Json;

/**
 * connector-jar 需求 7、9.3：真的 {@code @playwright/mcp}（用这台电脑上的 {@code npx}）驱动真的 Chrome 或 Edge、无头、本机测试站。
 * 走的是工具的定义加 {@link PlaywrightBrowser} 加 {@link PlaywrightHost}，和连接程序收到一个调用后走的路一样。没有 Node.js 或 Chrome、Edge
 * 时跳过并写明原因（CI 上跳过）。断言搬自原来的 {@code LocalBrowserIT}：模型看到的结果和以前一样。
 */
@Timeout(300)
class PlaywrightMcpIT {

    private static final Pattern REF = Pattern.compile("\"([^\"\\n]+)\"[^\\n]*\\[ref=((?:f\\d+)?e\\d+)]");

    @TempDir
    Path temp;

    private final List<PlaywrightHost> hosts = new ArrayList<>();
    private Optional<Path> npx;
    private Optional<Installed.Found> installed;
    private HttpServer site;
    private String base;
    private String download;

    @BeforeEach
    void start() throws IOException {
        npx = NodeFinder.npx();
        assumeTrue(npx.isPresent(), "Node.js (npx) is not installed on this machine");
        installed = Installed.find(Installed.Choice.ANY);
        assumeTrue(installed.isPresent(), "neither Google Chrome nor Microsoft Edge is installed on this machine; looked at "
                + Installed.searched(Installed.Choice.ANY));
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
        for (PlaywrightHost host : hosts) {
            host.close();
        }
        if (site != null) {
            site.stop(0);
        }
        if (installed != null && installed.isPresent()) {
            awaitNoBrowserProcesses();
        }
    }

    /** 一个无头的浏览器，资料目录在临时目录下的 {@code name}，和连接程序一样经 {@link PlaywrightHost}。 */
    private PlaywrightBrowser browser(String profile) throws Exception {
        Installed.Found found = installed.orElseThrow();
        BrowserSettings settings = new BrowserSettings(found.path(), found.name(), true, Profile.prepare(temp.resolve(profile)), npx.get());
        PlaywrightHost host = new PlaywrightHost(settings.command(temp.resolve("out")),
                tantalum.local.shell.CommandEnv.of(System.getenv(), List.of()), temp);
        hosts.add(host);
        host.start();
        long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        while (host.state() != PlaywrightHost.State.READY) {
            assertTrue(System.nanoTime() < deadline, "playwright MCP did not start: " + host.state() + " " + host.reason());
            Thread.sleep(100);
        }
        return new PlaywrightBrowser(host::call);
    }

    private LocalResult call(PlaywrightBrowser browser, String session, String tool, Object... pairs) throws Exception {
        Map<String, Json> arguments = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            Object value = pairs[i + 1];
            arguments.put((String) pairs[i], value instanceof Json json ? json : value instanceof Boolean flag ? new Json.Bool(flag)
                    : value instanceof Integer number ? new Json.Num(java.math.BigDecimal.valueOf(number)) : new Json.Str((String) value));
        }
        LocalTool definition = BrowserTools.all().stream().filter(candidate -> candidate.name().equals(tool)).findFirst().orElseThrow();
        LocalContext context = new LocalContext(session, new AllowedDirs(List.of(temp)), new Observations(),
                Shell.unavailable(Unavailable.of(Unavailable.Code.SHELL_NOT_ALLOWED)), null, browser);
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
        ProcessHandle.current().descendants().filter(PlaywrightMcpIT::isBrowserProcess).forEach(process -> pids.add(process.pid()));
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

    /** 11 个工具在真浏览器上各走一遍（搬自 LocalBrowserIT，断言不变；那里又搬自服务器的 BrowserToolsIT）。 */
    @Test
    void theElevenToolsWorkOnARealBrowser() throws Exception {
        PlaywrightBrowser browser = browser("p");
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
        assertEquals(PageText.UNTRUSTED, tabs.text().lines().findFirst().orElseThrow());
        LocalResult second = call(browser, "s1", "browser_tabs", "action", "select", "index", 2);
        assertTrue(second.text().contains("Page: Next") && second.text().contains("Tab 2 of 2"), second.text());
        call(browser, "s1", "browser_tabs", "action", "select", "index", 1);
        call(browser, "s1", "browser_click", "ref", ref(call(browser, "s1", "browser_snapshot"), "Next page"));
        LocalResult back = call(browser, "s1", "browser_navigate_back");
        assertTrue(back.text().contains("URL: " + base + "/\n"), back.text());
    }
}
