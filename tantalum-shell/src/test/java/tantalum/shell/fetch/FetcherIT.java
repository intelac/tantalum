package tantalum.shell.fetch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tantalum.shell.fetch.Fetcher.FetchFailed;
import tantalum.shell.fetch.Fetcher.Fetched;
import tantalum.shell.net.AddressRules;
import tantalum.shell.net.Egress;
import tantalum.shell.net.LogCapture;
import tantalum.shell.net.UpstreamProxy;

/**
 * {@code web_fetch} 的下载（W3、W4、W5、W6、W12、W13，需求 1.1–1.7、4.3、4.4）。本地 {@code com.sun.net.httpserver} 测试站，
 * 全程经过真实的 {@link Egress}，不访问外网。
 */
class FetcherIT {

    private static final String MARKER = "page-marker-91c4";

    private HttpServer site;
    private String base;
    private final Map<String, String> seenHeaders = new ConcurrentHashMap<>();
    private final List<AutoCloseable> cleanup = new ArrayList<>();

    @BeforeEach
    void startSite() throws IOException {
        site = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        site.createContext("/r/", exchange -> {
            int left = Integer.parseInt(exchange.getRequestURI().getPath().substring(3));
            exchange.getResponseHeaders().add("Location", left == 0 ? "/page" : "/r/" + (left - 1));
            send(exchange, 302, null, new byte[0]);
        });
        site.createContext("/page", exchange -> {
            exchange.getRequestHeaders().forEach((name, values) -> seenHeaders.put(name.toLowerCase(), String.join(",", values)));
            send(exchange, 200, "text/html; charset=utf-8",
                    ("<html><body><h1>Hello</h1><script>secret()</script><p>world " + MARKER + "</p></body></html>")
                            .getBytes(StandardCharsets.UTF_8));
        });
        site.createContext("/sjis", exchange -> send(exchange, 200, "text/html",
                "<html><head><meta charset=\"Shift_JIS\"></head><body><p>日本語のページ</p></body></html>".getBytes("Shift_JIS")));
        site.createContext("/gbk", exchange -> send(exchange, 200, "text/html",
                "<html><head><meta http-equiv=\"Content-Type\" content=\"text/html; charset=gbk\"></head><body><p>中文网页</p></body></html>"
                        .getBytes("GBK")));
        site.createContext("/header-wins", exchange -> send(exchange, 200, "text/html; charset=ISO-8859-1",
                "<html><head><meta charset=\"utf-8\"></head><body><p>café</p></body></html>".getBytes(StandardCharsets.ISO_8859_1)));
        site.createContext("/big", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, 6 * 1024 * 1024);
            byte[] chunk = "a".repeat(64 * 1024).getBytes(StandardCharsets.US_ASCII);
            try (OutputStream out = exchange.getResponseBody()) {
                for (int i = 0; i < 96; i++) {
                    out.write(chunk);
                }
            } catch (IOException stopped) {
                // 读的一方在 5 MB 处关了连接
            }
        });
        site.createContext("/doc.pdf", exchange -> send(exchange, 200, "application/pdf", "%PDF-1.7".getBytes(StandardCharsets.US_ASCII)));
        site.createContext("/pic.png", exchange -> send(exchange, 200, "image/png", new byte[] {(byte) 0x89, 'P', 'N', 'G'}));
        site.createContext("/missing", exchange -> send(exchange, 404, "text/html", "<p>No such page</p>".getBytes(StandardCharsets.UTF_8)));
        site.createContext("/broken", exchange -> send(exchange, 500, "text/plain", "internal error".getBytes(StandardCharsets.UTF_8)));
        site.start();
        base = "http://127.0.0.1:" + site.getAddress().getPort();
    }

    @AfterEach
    void stop() throws Exception {
        for (AutoCloseable closeable : cleanup.reversed()) {
            closeable.close();
        }
        site.stop(0);
    }

    private static void send(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        if (type != null) {
            exchange.getResponseHeaders().add("Content-Type", type);
        }
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            exchange.getResponseBody().write(body);
        }
        exchange.close();
    }

    private Fetcher fetcher(AddressRules rules) throws IOException {
        Egress egress = Egress.start(rules, UpstreamProxy.none());
        cleanup.add(egress);
        return new Fetcher(egress, rules);
    }

    private Fetcher open() throws IOException {
        return fetcher(new AddressRules(false));
    }

    @Test
    void htmlComesBackAsMarkdownWithAnHonestUserAgent() throws Exception {
        Fetched page = open().fetch(URI.create(base + "/page#section"));
        assertEquals(200, page.status());
        assertEquals("text/html; charset=utf-8", page.contentType());
        assertEquals("# Hello\n\nworld " + MARKER, page.text());
        assertFalse(page.cutAt5Mb());
        assertTrue(seenHeaders.get("user-agent").startsWith("Tantalum/"), seenHeaders.toString());
        assertFalse(seenHeaders.containsKey("accept-encoding"), seenHeaders.toString());
        assertFalse(seenHeaders.containsKey("cookie"), seenHeaders.toString());
    }

    /** 需求 1.3：最多跟 5 次，第 6 次报错。 */
    @Test
    void followsFiveRedirectsAndRefusesTheSixth() throws Exception {
        Fetched followed = open().fetch(URI.create(base + "/r/4"));
        assertEquals(URI.create(base + "/page"), followed.finalUrl());
        assertEquals(200, followed.status());
        FetchFailed tooMany = assertThrows(FetchFailed.class, () -> open().fetch(URI.create(base + "/r/5")));
        assertEquals("Too many redirects (more than 5).", tooMany.getMessage());
    }

    /** 需求 4.3：开关打开时回环被拦（测试里回环充当被拦的地址），开关关着时能打开；{@code localhost} 这个名字解析到回环，同样被拦。 */
    @Test
    void loopbackIsBlockedOnlyWithTheSwitchOn() throws Exception {
        FetchFailed blocked = assertThrows(FetchFailed.class, () -> fetcher(new AddressRules(true)).fetch(URI.create(base + "/page")));
        assertEquals("Blocked: 127.0.0.1 is a private network address, and this server does not allow private network access.",
                blocked.getMessage());
        String byName = "http://localhost:" + site.getAddress().getPort() + "/page";
        FetchFailed named = assertThrows(FetchFailed.class, () -> fetcher(new AddressRules(true)).fetch(URI.create(byName)));
        assertTrue(named.getMessage().startsWith("Blocked: localhost resolves to "), named.getMessage());
        assertTrue(named.getMessage().contains("a private network address"), named.getMessage());
        assertEquals(200, open().fetch(URI.create(base + "/page")).status());
    }

    /** 需求 4.3、4.4：每一跳都重新检查，跳到“自己的端口”被拦下，开关关着也一样；自己的端口一次都没被访问。 */
    @Test
    void redirectsToTheServersOwnPortAreBlockedWithTheSwitchOff() throws Exception {
        AtomicInteger ownHits = new AtomicInteger();
        HttpServer own = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        own.createContext("/", exchange -> {
            ownHits.incrementAndGet();
            send(exchange, 200, "text/plain", "api".getBytes(StandardCharsets.UTF_8));
        });
        own.start();
        cleanup.add(() -> own.stop(0));
        int ownPort = own.getAddress().getPort();
        site.createContext("/to-own", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + ownPort + "/api/sessions");
            send(exchange, 302, null, new byte[0]);
        });
        AddressRules rules = new AddressRules(false);
        rules.ownPort(ownPort);
        Fetcher fetcher = fetcher(rules);
        FetchFailed redirected = assertThrows(FetchFailed.class, () -> fetcher.fetch(URI.create(base + "/to-own")));
        assertEquals("Blocked: 127.0.0.1:" + ownPort + " is the Tantalum server itself.", redirected.getMessage());
        FetchFailed direct = assertThrows(FetchFailed.class, () -> fetcher.fetch(URI.create("http://localhost:" + ownPort + "/")));
        assertEquals("Blocked: localhost:" + ownPort + " is the Tantalum server itself.", direct.getMessage());
        assertEquals(0, ownHits.get());
    }

    /** 需求 1.6：编码只写在页面里时按页面解；头里写了以头为准。 */
    @Test
    void charsetsComeFromTheHeaderThenThePage() throws Exception {
        Fetcher fetcher = open();
        assertEquals("日本語のページ", fetcher.fetch(URI.create(base + "/sjis")).text());
        assertEquals("中文网页", fetcher.fetch(URI.create(base + "/gbk")).text());
        assertEquals("café", fetcher.fetch(URI.create(base + "/header-wins")).text());
        assertTrue(Charset.isSupported("Shift_JIS") && Charset.isSupported("GBK"));
    }

    /** 需求 1.4：下载到 5 MB 停止并记下。 */
    @Test
    void downloadsStopAtFiveMegabytes() throws Exception {
        Fetched big = open().fetch(URI.create(base + "/big"));
        assertTrue(big.cutAt5Mb());
        assertEquals(5 * 1024 * 1024, big.text().length());
    }

    /** 需求 1.5：PDF 和图片被拒，提示不同。 */
    @Test
    void pdfAndImagesAreRefusedWithDifferentHints() throws Exception {
        Fetcher fetcher = open();
        String pdf = assertThrows(FetchFailed.class, () -> fetcher.fetch(URI.create(base + "/doc.pdf"))).getMessage();
        String png = assertThrows(FetchFailed.class, () -> fetcher.fetch(URI.create(base + "/pic.png"))).getMessage();
        assertEquals("PDF files cannot be read yet: web_fetch does not convert them and the browser refuses downloads.", pdf);
        assertEquals("This is an image (image/png). Open it with browser_navigate and look at it with browser_take_screenshot.", png);
        assertNotEquals(pdf, png);
    }

    /** 需求 1.7：404、500 是正常结果，带状态码和正文。 */
    @Test
    void errorStatusesAreNormalResults() throws Exception {
        Fetcher fetcher = open();
        Fetched missing = fetcher.fetch(URI.create(base + "/missing"));
        assertEquals(404, missing.status());
        assertEquals("No such page", missing.text());
        Fetched broken = fetcher.fetch(URI.create(base + "/broken"));
        assertEquals(500, broken.status());
        assertEquals("internal error", broken.text());
    }

    /** W13：连不上算错误，原因只写类别。 */
    @Test
    void unreachableSitesAreErrorsWithACategory() throws Exception {
        int port;
        try (java.net.ServerSocket gone = new java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = gone.getLocalPort();
        }
        FetchFailed failed = assertThrows(FetchFailed.class, () -> open().fetch(URI.create("http://127.0.0.1:" + port + "/x")));
        assertEquals("Could not fetch http://127.0.0.1:" + port + "/x: the site could not be reached.", failed.getMessage());
    }

    /** NFR Security：页面内容、路径里的令牌都不进日志。 */
    @Test
    void pageContentAndPathsStayOutOfTheLogs() throws Exception {
        try (LogCapture log = new LogCapture()) {
            open().fetch(URI.create(base + "/page?token=path-token-55e1"));
            assertThrows(FetchFailed.class, () -> fetcher(new AddressRules(true)).fetch(URI.create(base + "/page?token=path-token-55e1")));
            String all = log.all();
            assertFalse(all.contains(MARKER), all);
            assertFalse(all.contains("path-token-55e1"), all);
        }
    }
}
