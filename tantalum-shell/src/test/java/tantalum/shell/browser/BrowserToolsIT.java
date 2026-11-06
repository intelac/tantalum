package tantalum.shell.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.attachment.AttachmentLimits;
import tantalum.shell.attachment.AttachmentStore;
import tantalum.shell.attachment.ImageNormalizer;
import tantalum.shell.attachment.ToolImages;
import tantalum.shell.net.AddressRules;
import tantalum.shell.net.Egress;
import tantalum.shell.net.UpstreamProxy;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.ToolOutput;
import tantalum.shell.plugin.Workspace;
import tantalum.value.attachment.AttachmentText;
import tantalum.value.attachment.ImageRef;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;

/**
 * 任务 18 的工具在真实 Chromium 上走一遍（需求 2.2、2.3、2.7、3.1、4.2）：本地测试站、真实出口，不访问外网。
 * 没装 Chromium headless shell 时跳过并写明原因。隔离、空闲、崩溃这些生命周期的用例在任务 20 的 {@code BrowsersIT}。
 */
@Timeout(180)
class BrowserToolsIT {

    private static final Principal ALICE = new Principal("alice");
    private static final Pattern REF = Pattern.compile("\"([^\"\\n]+)\"[^\\n]*\\[ref=(e\\d+)]");

    @TempDir
    Path temp;

    private final List<AutoCloseable> cleanup = new ArrayList<>();
    private HttpServer site;
    private String base;
    private AttachmentStore store;

    @BeforeEach
    void start() throws IOException {
        site = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        page("/", "<html><head><title>Form</title></head><body><h1>Hello</h1>"
                + "<input aria-label='Query' id='q'><button onclick=\"document.title='clicked ' + q.value\">Go</button>"
                + "<select aria-label='Color'><option value='r'>Red</option><option value='g'>Green</option></select>"
                + "<a href='/next'>Next page</a><a href='/next' target='_blank'>New tab</a>"
                + "<script>setTimeout(() => document.body.insertAdjacentHTML('beforeend', '<p>Arrived later</p>'), 300)</script>"
                + "</body></html>");
        page("/next", "<html><head><title>Next</title></head><body><p>second page</p></body></html>");
        site.createContext("/file.bin", exchange -> {
            exchange.getResponseHeaders().add("Content-Disposition", "attachment; filename=file.bin");
            exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, 3);
            exchange.getResponseBody().write(new byte[] {1, 2, 3});
            exchange.close();
        });
        site.start();
        base = "http://127.0.0.1:" + site.getAddress().getPort();
        store = new AttachmentStore(temp.resolve("data"), AttachmentLimits.defaults(), new ImageNormalizer(AttachmentLimits.defaults()));
    }

    private void page(String path, String html) {
        site.createContext(path, exchange -> {
            byte[] body = html.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
    }

    @AfterEach
    void stop() throws Exception {
        for (AutoCloseable closeable : cleanup.reversed()) {
            closeable.close();
        }
        site.stop(0);
    }

    private Map<String, Tool> tools(boolean blockPrivateNetwork) throws IOException {
        Egress egress = Egress.start(new AddressRules(blockPrivateNetwork), UpstreamProxy.none());
        cleanup.add(egress);
        Browsers browsers = new Browsers(new Browsers.Settings(2, Duration.ofMinutes(10), true), egress, new ToolImages(store),
                Clock.systemUTC(), new BrowserDriver(UserBrowserIT.DRIVER_DATA));
        cleanup.add(browsers);
        Map<String, Tool> tools = new LinkedHashMap<>();
        BrowserTools.all(browsers).forEach(tool -> tools.put(tool.name(), tool));
        return tools;
    }

    private static ToolOutput call(Tool tool, Object... pairs) throws Exception {
        Map<String, Json> arguments = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            Object value = pairs[i + 1];
            arguments.put((String) pairs[i], value instanceof Json json ? json : value instanceof Boolean flag ? new Json.Bool(flag)
                    : value instanceof Integer number ? new Json.Num(java.math.BigDecimal.valueOf(number)) : new Json.Str((String) value));
        }
        return tool.run(new ToolContext(ALICE, new SessionId("s1"), new Workspace(Path.of(System.getProperty("java.io.tmpdir")))),
                new Json.Obj(arguments));
    }

    private static String ref(ToolOutput snapshot, String name) {
        Matcher matcher = REF.matcher(snapshot.text());
        while (matcher.find()) {
            if (matcher.group(1).equals(name)) {
                return matcher.group(2);
            }
        }
        throw new AssertionError("no ref for " + name + " in\n" + snapshot.text());
    }

    @Test
    void theElevenToolsWorkOnARealBrowser() throws Exception {
        Map<String, Tool> tools = tools(false);
        ToolOutput opened = call(tools.get("browser_navigate"), "url", base + "/");
        assumeTrue(!opened.text().equals(UserBrowser.NOT_INSTALLED),
                "Chromium headless shell is not installed here; run: java -jar tantalum.jar browser install");
        assertFalse(opened.error(), opened.text());
        assertEquals(PageText.UNTRUSTED, opened.text().lines().findFirst().orElseThrow());
        assertTrue(opened.text().contains("Page: Form\nURL: " + base + "/\nTab 1 of 1"), opened.text());

        // 按编号输入、点击：结果里是新的快照
        ToolOutput typed = call(tools.get("browser_type"), "ref", ref(opened, "Query"), "text", "bing");
        assertFalse(typed.error(), typed.text());
        ToolOutput clicked = call(tools.get("browser_click"), "ref", ref(typed, "Go"));
        assertTrue(clicked.text().contains("Page: clicked bing"), clicked.text());
        ToolOutput stale = call(tools.get("browser_click"), "ref", "e999");
        assertEquals("Element ref e999 is not on the current page; call browser_snapshot for fresh refs.", stale.text());

        ToolOutput selected = call(tools.get("browser_select_option"), "ref", ref(clicked, "Color"),
                "values", new Json.Arr(List.of(new Json.Str("g"))));
        assertFalse(selected.error(), selected.text());
        Json.Str expression = new Json.Str("() => ({color: document.querySelector('select').value, n: 1 + 1})");
        ToolOutput evaluated = call(tools.get("browser_evaluate"), "expression", expression);
        assertEquals(PageText.UNTRUSTED + "\n{\"color\":\"g\",\"n\":2}", evaluated.text());

        ToolOutput waited = call(tools.get("browser_wait_for"), "text", "Arrived later");
        assertTrue(waited.text().contains("Arrived later"), waited.text());
        assertFalse(call(tools.get("browser_press_key"), "key", "Tab").error());

        // 截图进本人附件，结果里是引用（需求 3.1）
        ToolOutput shot = call(tools.get("browser_take_screenshot"));
        assertFalse(shot.error(), shot.text());
        ImageRef image = shot.images().getFirst();
        assertEquals("screenshot-127.0.0.1.png", image.name());
        assertEquals(PageText.UNTRUSTED + "\nScreenshot of " + base + "/\n" + AttachmentText.image(image), shot.text());
        assertTrue(store.find(ALICE, image.id()).isPresent());
        assertTrue(store.find(new Principal("bob"), image.id()).isEmpty());

        // 网页开的新窗口出现在标签页列表里，选中后成为当前页；后退
        call(tools.get("browser_click"), "ref", ref(call(tools.get("browser_snapshot")), "New tab"));
        ToolOutput tabs = call(tools.get("browser_tabs"), "action", "list");
        for (int i = 0; i < 50 && !tabs.text().contains("2. Next"); i++) {
            Thread.sleep(100); // 新窗口的事件随下一次调用送到
            tabs = call(tools.get("browser_tabs"), "action", "list");
        }
        assertTrue(tabs.text().contains("* 1. ") && tabs.text().contains("  2. Next - " + base + "/next"), tabs.text());
        ToolOutput second = call(tools.get("browser_tabs"), "action", "select", "index", 2);
        assertTrue(second.text().contains("Page: Next") && second.text().contains("Tab 2 of 2"), second.text());
        call(tools.get("browser_tabs"), "action", "select", "index", 1);
        call(tools.get("browser_click"), "ref", ref(call(tools.get("browser_snapshot")), "Next page"));
        ToolOutput back = call(tools.get("browser_navigate_back"));
        assertTrue(back.text().contains("URL: " + base + "/\n"), back.text());

        // 下载被拒（需求 2.3）
        ToolOutput download = call(tools.get("browser_navigate"), "url", base + "/file.bin");
        assertEquals("This URL starts a file download; downloads are not allowed.", download.text());
    }

    /** 需求 4.2：开关打开时，网址里的主机是回环地址，打开前就给清楚的原因，不起浏览器也行。 */
    @Test
    void blockedHostsGetTheAddressRuleSentence() throws Exception {
        Map<String, Tool> tools = tools(true);
        ToolOutput blocked = call(tools.get("browser_navigate"), "url", base + "/");
        assertTrue(blocked.error());
        assertEquals("Blocked: 127.0.0.1 is a private network address, and this server does not allow private network access.",
                blocked.text());
    }
}
