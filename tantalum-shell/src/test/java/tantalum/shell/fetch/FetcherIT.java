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
        site.createContext("/article", exchange -> send(exchange, 200, "text/html; charset=utf-8", ARTICLE));
        site.start();
        base = "http://127.0.0.1:" + site.getAddress().getPort();
    }

    /** 约 100 KB 的文章页：导航、脚本、样式、段落、链接、列表、表格、隐藏内容，像一篇普通的文档页。 */
    private static final byte[] ARTICLE = article();

    private static byte[] article() {
        StringBuilder html = new StringBuilder("<!doctype html><html><head><meta charset=\"utf-8\"><title>Article</title>"
                + "<style>body{font:14px sans-serif}.x{display:none}</style><script>window.a = 1;</script></head><body>"
                + "<nav><ul><li><a href=\"/\">Home</a></li><li><a href=\"/docs\">Docs</a></li></ul></nav><main><h1>Article</h1>");
        for (int section = 1; html.length() < 100 * 1024; section++) {
            html.append("<h2>Section ").append(section).append("</h2>");
            for (int p = 0; p < 4; p++) {
                html.append("<p>Paragraph ").append(p).append(" of section ").append(section)
                        .append(" with <a href=\"/ref/").append(section).append('-').append(p).append("\">a link</a>, <b>bold</b> text,"
                                + " <code>inline code</code> and enough words to look like an ordinary sentence in a document.</p>");
            }
            html.append("<ul><li>first point</li><li>second point<ul><li>nested</li></ul></li></ul>")
                    .append("<table><tr><th>Name</th><th>Value</th></tr><tr><td>a|b</td><td>").append(section).append("</td></tr></table>")
                    .append("<div class=\"x\" style=\"display:none\">hidden ").append(section).append("</div>")
                    .append("<pre><code>for (int i = 0; i < ").append(section).append("; i++) {}</code></pre>");
        }
        return html.append("</main><script>track();</script></body></html>").toString().getBytes(StandardCharsets.UTF_8);
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

    /**
     * NFR Performance：100 KB 的 HTML 从收到正文到结果（解码、jsoup 解析、转 Markdown、拼结果）≤ 200 ms，热身 20 次后量 100 次取 P95。
     * 另量整次 {@code fetch}（本机回环、经出口）的 P95 作参考，它是上面那段时间的上限。数字记在 tech.md。
     */
    @Test
    void aHundredKilobytePageIsProcessedWellUnderTwoHundredMilliseconds() throws Exception {
        assertTrue(ARTICLE.length >= 100 * 1024 && ARTICLE.length < 110 * 1024, ARTICLE.length + " bytes");
        URI url = URI.create(base + "/article");
        long[] processing = new long[100];
        String text = "";
        for (int i = -20; i < processing.length; i++) {
            long started = System.nanoTime();
            String markdown = HtmlMarkdown.of(org.jsoup.Jsoup.parse(new java.io.ByteArrayInputStream(ARTICLE), null, url.toString()));
            text = WebFetchTool.text(new Fetched(url, 200, "text/html; charset=utf-8", markdown, false));
            if (i >= 0) {
                processing[i] = System.nanoTime() - started;
            }
        }
        assertTrue(text.contains("# Article") && !text.contains("hidden 1") && !text.contains("track()"), text.substring(0, 300));

        Fetcher fetcher = open();
        long[] whole = new long[100];
        for (int i = -20; i < whole.length; i++) {
            long started = System.nanoTime();
            Fetched page = fetcher.fetch(url);
            WebFetchTool.text(page);
            if (i >= 0) {
                whole[i] = System.nanoTime() - started;
            }
        }
        double processingP95 = p95(processing);
        double wholeP95 = p95(whole);
        System.out.printf(java.util.Locale.ROOT, "NFR web_fetch: %d bytes, processing P95 %.1f ms (median %.1f), whole fetch P95 %.1f ms%n",
                ARTICLE.length, processingP95, median(processing), wholeP95);
        assertTrue(processingP95 <= 200, "processing P95 " + processingP95 + " ms");
    }

    private static double p95(long[] nanos) {
        long[] sorted = nanos.clone();
        java.util.Arrays.sort(sorted);
        return sorted[(int) Math.ceil(sorted.length * 0.95) - 1] / 1e6;
    }

    private static double median(long[] nanos) {
        long[] sorted = nanos.clone();
        java.util.Arrays.sort(sorted);
        return sorted[sorted.length / 2] / 1e6;
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
