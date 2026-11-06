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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
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
import tantalum.shell.net.LogCapture;
import tantalum.shell.net.UpstreamProxy;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.ToolOutput;
import tantalum.shell.plugin.Workspace;
import tantalum.value.attachment.ImageRef;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;

/**
 * 任务 20：真实 Chromium、本地测试站、真实出口，测每人一个浏览器的隔离、生命周期和地址规则（需求 2.1–2.5、2.8、3.1、4.1、4.2、4.4、
 * NFR Reliability）。不访问外网；没装 Chromium headless shell 时跳过并写明原因。每个用例结束时没有驱动和 Chromium 子进程。
 */
@Timeout(240)
class BrowsersIT {

    private static final Principal ALICE = new Principal("alice");
    private static final Principal BOB = new Principal("bob");
    private static final Pattern REF = Pattern.compile("\"([^\"\\n]+)\"[^\\n]*\\[ref=((?:f\\d+)?e\\d+)]");

    @TempDir
    Path temp;

    private final List<AutoCloseable> cleanup = new ArrayList<>();
    private final AtomicInteger ownPortHits = new AtomicInteger();
    private final String download = "tantalum-it-" + UUID.randomUUID() + ".bin";
    private HttpServer site;
    private HttpServer ownServer;
    private String base;
    private AttachmentStore store;
    private Browsers browsers;
    private Map<String, Tool> tools;

    @BeforeEach
    void start() throws IOException {
        // 当作“Tantalum 自己的端口”的另一个本地站：页面里指向它的图片应该被出口拦下，一次也到不了它
        ownServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        ownServer.createContext("/", exchange -> {
            ownPortHits.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        ownServer.start();
        site = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        site.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String cookie = exchange.getRequestHeaders().getFirst("Cookie");
            if (path.equals("/file.bin")) {
                exchange.getResponseHeaders().add("Content-Disposition", "attachment; filename=" + download);
                exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
                exchange.sendResponseHeaders(200, 3);
                exchange.getResponseBody().write(new byte[] {1, 2, 3});
                exchange.close();
                return;
            }
            if (path.equals("/login")) {
                exchange.getResponseHeaders().add("Set-Cookie", "who=alice; Path=/");
            }
            String html = switch (path) {
                case "/form" -> "<html><head><title>Form</title></head><body><input aria-label='Query' id='q'>"
                        + "<button onclick=\"document.title='clicked ' + q.value\">Go</button>"
                        + "<a href='/file.bin'>Get file</a></body></html>";
                case "/own" -> "<html><head><title>Own port</title></head><body><p>picture below</p>"
                        + "<img src='http://127.0.0.1:" + ownServer.getAddress().getPort() + "/pixel.png'></body></html>";
                default -> "<html><head><title>" + path.substring(1) + "</title></head><body><p>cookie=" + (cookie == null ? "none" : cookie)
                        + "</p></body></html>";
            };
            byte[] body = html.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        site.start();
        base = "http://127.0.0.1:" + site.getAddress().getPort();
        store = new AttachmentStore(temp.resolve("data"), AttachmentLimits.defaults(), new ImageNormalizer(AttachmentLimits.defaults()));
    }

    @AfterEach
    void stop() throws Exception {
        for (AutoCloseable closeable : cleanup.reversed()) {
            closeable.close();
        }
        site.stop(0);
        ownServer.stop(0);
        awaitNoBrowserProcesses();
    }

    /** 和服务器一样的组装，只是空闲检查从 30 秒一次改成半秒一次。 */
    private void browsers(int maxOpen, Duration idleClose, boolean blockPrivateNetwork) throws IOException {
        AddressRules rules = new AddressRules(blockPrivateNetwork);
        rules.ownPort(ownServer.getAddress().getPort());
        Egress egress = Egress.start(rules, UpstreamProxy.none());
        cleanup.add(egress);
        BrowserDriver driver = new BrowserDriver(UserBrowserIT.DRIVER_DATA);
        Clock clock = Clock.systemUTC();
        browsers = new Browsers(new Browsers.Settings(maxOpen, idleClose, true), egress, new ToolImages(store), clock,
                () -> UserBrowser.open(driver, egress.port(), true, clock), Duration.ofMillis(500));
        cleanup.add(browsers);
        tools = new LinkedHashMap<>();
        BrowserTools.all(browsers).forEach(tool -> tools.put(tool.name(), tool));
    }

    private ToolOutput call(Principal user, String session, String tool, Object... pairs) throws Exception {
        Map<String, Json> arguments = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            Object value = pairs[i + 1];
            arguments.put((String) pairs[i], value instanceof Integer number ? new Json.Num(java.math.BigDecimal.valueOf(number))
                    : new Json.Str((String) value));
        }
        return tools.get(tool).run(new ToolContext(user, new SessionId(session), new Workspace(temp)), new Json.Obj(arguments));
    }

    private ToolOutput open(Principal user, String session, String path) throws Exception {
        ToolOutput opened = call(user, session, "browser_navigate", "url", base + path);
        assumeTrue(!opened.text().equals(UserBrowser.NOT_INSTALLED),
                "Chromium headless shell is not installed here; run: java -jar tantalum.jar browser install");
        return opened;
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

    // ---- 子进程 ----

    private static boolean isBrowserProcess(ProcessHandle process) {
        return process.info().command().map(command -> command.contains("chrome") || command.endsWith("node")).orElse(false);
    }

    private static Set<Long> browserProcesses() {
        Set<Long> pids = new HashSet<>();
        ProcessHandle.current().descendants().filter(BrowsersIT::isBrowserProcess).forEach(process -> pids.add(process.pid()));
        return pids;
    }

    private static void awaitNoBrowserProcesses() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!browserProcesses().isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        assertEquals(Set.of(), browserProcesses(), "no driver or Chromium left");
    }

    /** 这一组里的 Chromium 主进程：父进程是驱动（node）。 */
    private static ProcessHandle chromiumMain(Set<Long> group) {
        return group.stream().map(ProcessHandle::of).flatMap(java.util.Optional::stream)
                .filter(process -> process.info().command().map(command -> command.contains("chrome")).orElse(false))
                .filter(process -> process.parent().flatMap(parent -> parent.info().command()).map(command -> command.endsWith("node"))
                        .orElse(false))
                .findFirst().orElseThrow(() -> new AssertionError("no Chromium main process in " + group));
    }

    // ---- 用例 ----

    /** 需求 2.1、2.2：A 登录后 B 看不到 A 的 Cookie 和标签页；按编号输入、点击；同一用户的两个会话各有自己的当前页。 */
    @Test
    void usersAreIsolatedAndEachSessionHasItsOwnCurrentPage() throws Exception {
        browsers(4, Duration.ofMinutes(10), false);
        assertFalse(open(ALICE, "a1", "/login").error());
        assertTrue(call(ALICE, "a1", "browser_navigate", "url", base + "/alice-page").text().contains("cookie=who=alice"));
        ToolOutput bob = open(BOB, "b1", "/whoami");
        assertTrue(bob.text().contains("cookie=none"), bob.text());
        ToolOutput bobTabs = call(BOB, "b1", "browser_tabs", "action", "list");
        assertFalse(bobTabs.text().contains("alice-page"), bobTabs.text());
        assertEquals(1, bobTabs.text().lines().filter(line -> line.contains(" - " + base)).count(), bobTabs.text());

        ToolOutput form = call(ALICE, "a1", "browser_navigate", "url", base + "/form");
        assertTrue(form.text().contains("[ref="), form.text());
        call(ALICE, "a1", "browser_type", "ref", ref(form, "Query"), "text", "hello");
        ToolOutput clicked = call(ALICE, "a1", "browser_click", "ref", ref(form, "Go"));
        assertTrue(clicked.text().contains("Page: clicked hello"), clicked.text());

        // 同一个用户的第二个会话：同一个浏览器（Cookie 共享），但有自己的当前页，不翻掉 a1 的
        ToolOutput second = call(ALICE, "a2", "browser_navigate", "url", base + "/whoami");
        assertTrue(second.text().contains("cookie=who=alice"), second.text());
        assertTrue(call(ALICE, "a1", "browser_snapshot").text().contains("Page: clicked hello"));
        assertEquals(2, browsers.openCount());
    }

    /** 需求 2.3、3.1：点下载链接报错，临时目录、数据目录和 ~/Downloads 都没有这个文件；截图进本人附件，结果里是引用。 */
    @Test
    void downloadsLeaveNoFileAndScreenshotsGoToTheUsersAttachments() throws Exception {
        browsers(4, Duration.ofMinutes(10), false);
        ToolOutput form = open(ALICE, "a1", "/form");
        Set<Path> tmpBefore = listing(Path.of(System.getProperty("java.io.tmpdir")));
        ToolOutput clicked = call(ALICE, "a1", "browser_click", "ref", ref(form, "Get file"));
        ToolOutput direct = call(ALICE, "a1", "browser_navigate", "url", base + "/file.bin");
        assertEquals("This URL starts a file download; downloads are not allowed.", direct.text());
        assertTrue(direct.error());
        assertFalse(clicked.text().contains(download), "the click result does not name a saved file: " + clicked.text());
        for (Path dir : List.of(Path.of(System.getProperty("java.io.tmpdir")), UserBrowserIT.DRIVER_DATA, temp,
                Path.of(System.getProperty("user.home"), "Downloads"))) {
            assertEquals(List.of(), find(dir, download), "downloaded file in " + dir);
        }
        Set<Path> artifacts = new HashSet<>(listing(Path.of(System.getProperty("java.io.tmpdir"))));
        artifacts.removeAll(tmpBefore);
        artifacts.removeIf(path -> !path.getFileName().toString().startsWith("playwright-artifacts"));
        for (Path dir : artifacts) {
            assertEquals(List.of(), find(dir, ""), "Playwright kept a download in " + dir);
        }

        ToolOutput shot = call(ALICE, "a1", "browser_take_screenshot");
        assertFalse(shot.error(), shot.text());
        ImageRef image = shot.images().getFirst();
        assertTrue(store.find(ALICE, image.id()).isPresent());
        assertTrue(store.find(BOB, image.id()).isEmpty());
    }

    /** 需求 2.4：空闲 2 秒后关掉，子进程没了；再调用时重新打开，Cookie 和标签页都没了。 */
    @Test
    void anIdleBrowserIsClosedAndAFreshOneOpensNextTime() throws Exception {
        browsers(4, Duration.ofSeconds(2), false);
        open(ALICE, "a1", "/login");
        assertEquals(1, browsers.openCount());
        assertFalse(browserProcesses().isEmpty());
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (browsers.openCount() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(200);
        }
        assertEquals(0, browsers.openCount(), "closed after 2 s without use");
        awaitNoBrowserProcesses();
        ToolOutput again = call(ALICE, "a1", "browser_navigate", "url", base + "/whoami");
        assertTrue(again.text().contains("cookie=none") && again.text().contains("Tab 1 of 1"), again.text());
    }

    /** 需求 2.5：上限 2 时第三个用户得到“都在用”，不挤掉前两个。 */
    @Test
    void theThirdUserIsToldAllBrowsersAreInUse() throws Exception {
        browsers(2, Duration.ofMinutes(10), false);
        open(ALICE, "a1", "/one");
        assertFalse(open(BOB, "b1", "/two").error());
        ToolOutput carol = call(new Principal("carol"), "c1", "browser_navigate", "url", base + "/three");
        assertTrue(carol.error());
        assertEquals("All browsers on this server are in use (2 of 2). Try again in a few minutes.", carol.text());
        assertTrue(call(ALICE, "a1", "browser_snapshot").text().contains("Page: one"));
        assertTrue(call(BOB, "b1", "browser_snapshot").text().contains("Page: two"));
    }

    /** NFR Reliability、需求 2.8：杀掉一个用户的 Chromium，这次得到崩溃的说明、下次正常，另一个用户不受影响；close() 后没有子进程。 */
    @Test
    void aCrashedBrowserIsReplacedWithoutTouchingOtherUsers() throws Exception {
        browsers(4, Duration.ofMinutes(10), false);
        open(ALICE, "a1", "/one");
        Set<Long> alice = browserProcesses();
        open(BOB, "b1", "/two");
        Set<Long> bob = new HashSet<>(browserProcesses());
        bob.removeAll(alice);
        assertFalse(bob.isEmpty());

        ProcessHandle chromium = chromiumMain(alice);
        chromium.destroyForcibly();
        chromium.onExit().get(10, java.util.concurrent.TimeUnit.SECONDS);
        ToolOutput crashed = call(ALICE, "a1", "browser_snapshot");
        for (int i = 0; i < 20 && !crashed.text().equals(Browsers.CRASHED); i++) {
            Thread.sleep(250); // 断开的消息随下一次调用送到
            crashed = call(ALICE, "a1", "browser_snapshot");
        }
        assertEquals(Browsers.CRASHED, crashed.text());
        assertTrue(call(BOB, "b1", "browser_snapshot").text().contains("Page: two"), "bob is not affected");
        ToolOutput reopened = call(ALICE, "a1", "browser_navigate", "url", base + "/again");
        assertFalse(reopened.error(), reopened.text());
        assertTrue(reopened.text().contains("Page: again"), reopened.text());
        assertEquals(2, browsers.openCount());

        browsers.close();
        awaitNoBrowserProcesses();
    }

    /** 需求 4.1、4.2、4.4：开关开时回环上的页被拦，关着时能打开；页面里指向“自己的端口”的图片被出口拦下，一次也没到那个端口。 */
    @Test
    void addressRulesApplyToPagesAndToTheirSubrequests() throws Exception {
        browsers(4, Duration.ofMinutes(10), true);
        ToolOutput blocked = call(ALICE, "a1", "browser_navigate", "url", base + "/one");
        assertTrue(blocked.error());
        assertTrue(blocked.text().contains("private network address"), blocked.text());
        assertEquals(0, browsers.openCount(), "blocked before a browser starts");
        browsers.close();

        browsers(4, Duration.ofMinutes(10), false);
        try (LogCapture logs = new LogCapture()) {
            ToolOutput page = open(ALICE, "a1", "/own");
            assertFalse(page.error(), page.text());
            assertTrue(page.text().contains("Page: Own port"), page.text());
            call(ALICE, "a1", "browser_wait_for", "seconds", 1);
            assertEquals(0, ownPortHits.get(), "the image request never reached the own port");
            String own = "web egress blocked 127.0.0.1:" + ownServer.getAddress().getPort() + " (own-port)";
            assertTrue(logs.lines().stream().anyMatch(line -> line.endsWith(own)), logs.all());
            assertFalse(logs.all().contains("/pixel.png"), "egress logs carry no paths");
        }
    }

    private static Set<Path> listing(Path dir) throws IOException {
        try (Stream<Path> entries = Files.list(dir)) {
            return new HashSet<>(entries.toList());
        }
    }

    /** {@code dir} 下（三层以内）名字含 {@code name} 的文件；读不到的目录跳过。 */
    private static List<Path> find(Path dir, String name) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<Path> found = new ArrayList<>();
        Files.walkFileTree(dir, Set.of(), 3, new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attributes) {
                if (file.getFileName().toString().contains(name)) {
                    found.add(file);
                }
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFileFailed(Path file, IOException failure) {
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
        return found;
    }
}
