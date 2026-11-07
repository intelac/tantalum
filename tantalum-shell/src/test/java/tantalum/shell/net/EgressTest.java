package tantalum.shell.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 出口（W6、W12，需求 4.2、4.4，NFR Security）。全部用本地测试站和假的上游代理，不访问外网。 */
class EgressTest {

    private final List<AutoCloseable> cleanup = new ArrayList<>();

    @TempDir
    Path temp;

    @AfterEach
    void tearDown() throws Exception {
        for (AutoCloseable closeable : cleanup.reversed()) {
            closeable.close();
        }
    }

    private Egress egress(AddressRules rules, UpstreamProxy upstream) throws IOException {
        Egress egress = Egress.start(rules, upstream);
        cleanup.add(egress);
        return egress;
    }

    /** 本地测试站，记下被访问了几次。 */
    private record Site(HttpServer server, AtomicInteger hits) {
        int port() {
            return server.getAddress().getPort();
        }
    }

    private Site site(String body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        cleanup.add(() -> server.stop(0));
        return new Site(server, hits);
    }

    /** 一条连接上原样发出去，读到对方关连接为止。 */
    private static String raw(int port, String request) throws IOException {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }

    @Test
    void listensOnlyOnLoopback() throws Exception {
        Egress egress = egress(new AddressRules(false), UpstreamProxy.none());
        InetAddress nic = null;
        for (NetworkInterface face : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            for (InetAddress address : Collections.list(face.getInetAddresses())) {
                if (!address.isLoopbackAddress() && !address.isLinkLocalAddress()) {
                    nic = address;
                }
            }
        }
        assumeTrue(nic != null, "this machine has no non-loopback interface address");
        InetAddress lan = nic;
        assertThrows(ConnectException.class, () -> new Socket(lan, egress.port()).close());
        new Socket(InetAddress.getLoopbackAddress(), egress.port()).close();
    }

    /** CONNECT 到本地 https 测试站：出口只转字节，TLS 在两头之间完成。 */
    @Test
    void connectReachesALocalHttpsSite() throws Exception {
        SSLContext tls = selfSigned();
        HttpsServer server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(tls));
        server.createContext("/", exchange -> {
            byte[] bytes = "secure hello".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        cleanup.add(() -> server.stop(0));
        Egress egress = egress(new AddressRules(false), UpstreamProxy.none());
        try (HttpClient client = HttpClient.newBuilder().sslContext(tls)
                .proxy(ProxySelector.of(new InetSocketAddress(InetAddress.getLoopbackAddress(), egress.port()))).build()) {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create("https://localhost:" + server.getAddress().getPort() + "/")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals("secure hello", response.body());
        }
    }

    @Test
    void blockedAddressesGet403WithTheReasonHeader() throws Exception {
        Site site = site("inside");
        Egress egress = egress(new AddressRules(true), UpstreamProxy.none());
        HttpResponse<String> plain = egress.client().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + site.port() + "/")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, plain.statusCode());
        assertEquals("private-network", plain.headers().firstValue(Egress.BLOCKED_HEADER).orElseThrow());
        assertEquals("Blocked: 127.0.0.1 is a private network address, and this server does not allow private network access.",
                plain.body());
        String tunnel = raw(egress.port(), "CONNECT localhost:" + site.port() + " HTTP/1.1\r\nHost: localhost\r\n\r\n");
        assertTrue(tunnel.startsWith("HTTP/1.1 403"), tunnel);
        assertTrue(tunnel.contains(Egress.BLOCKED_HEADER + ": private-network"), tunnel);
        assertEquals(0, site.hits().get());
    }

    /** 自己的端口不论开关都拦；出口自己的端口也算。 */
    @Test
    void ownPortsAreBlockedWithTheSwitchOff() throws Exception {
        Site server = site("tantalum api");
        AddressRules rules = new AddressRules(false);
        rules.ownPort(server.port());
        Egress egress = egress(rules, UpstreamProxy.none());
        String toServer = raw(egress.port(), "CONNECT 127.0.0.1:" + server.port() + " HTTP/1.1\r\n\r\n");
        assertTrue(toServer.startsWith("HTTP/1.1 403") && toServer.contains(Egress.BLOCKED_HEADER + ": own-port"), toServer);
        String toEgress = raw(egress.port(), "GET http://127.0.0.1:" + egress.port() + "/ HTTP/1.1\r\nHost: x\r\n\r\n");
        assertTrue(toEgress.startsWith("HTTP/1.1 403") && toEgress.contains(Egress.BLOCKED_HEADER + ": own-port"), toEgress);
        assertEquals(0, server.hits().get());
    }

    /**
     * 设计时实测的漏洞的回归：同一条连接上的第二个明文请求指向被拦的端口，也要被拦。出口一条连接只转一个请求，
     * 第二个请求的字节被丢掉，自己的端口一次都没被访问。
     */
    @Test
    void aSecondPlainRequestOnTheSameConnectionIsNeverForwarded() throws Exception {
        Site outside = site("outside");
        Site server = site("tantalum api");
        AddressRules rules = new AddressRules(false);
        rules.ownPort(server.port());
        Egress egress = egress(rules, UpstreamProxy.none());
        String both = raw(egress.port(),
                "GET http://127.0.0.1:" + outside.port() + "/a HTTP/1.1\r\nHost: 127.0.0.1:" + outside.port() + "\r\n\r\n"
                        + "GET http://127.0.0.1:" + server.port() + "/api HTTP/1.1\r\nHost: 127.0.0.1:" + server.port() + "\r\n\r\n");
        assertTrue(both.startsWith("HTTP/1.1 200") && both.contains("outside"), both);
        assertTrue(both.toLowerCase().contains("connection: close"), both);
        assertFalse(both.contains("tantalum api"), both);
        assertEquals(1, outside.hits().get());

        // 同一个 HttpClient 连着发两次（实测时它复用了到代理的连接）
        HttpClient client = egress.client();
        HttpResponse<String> first = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + outside.port() + "/b")).build(),
                HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> second = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals("outside", first.body());
        assertEquals(403, second.statusCode());
        assertEquals("own-port", second.headers().firstValue(Egress.BLOCKED_HEADER).orElseThrow());
        assertEquals(0, server.hits().get());
    }

    /** 出口不认的请求正文（分块编码、两个长度头）不转发：正文边界两边认得不一样，多出的字节就成了夹带的第二个请求。 */
    @Test
    void ambiguousRequestBodiesAreRefused() throws Exception {
        Site site = site("inside");
        Egress egress = egress(new AddressRules(false), UpstreamProxy.none());
        String target = "POST http://127.0.0.1:" + site.port() + "/ HTTP/1.1\r\nHost: 127.0.0.1\r\n";
        String chunked = raw(egress.port(), target + "Transfer-Encoding: chunked\r\n\r\n0\r\n\r\n");
        assertTrue(chunked.startsWith("HTTP/1.1 411"), chunked);
        String twice = raw(egress.port(), target + "Content-Length: 0\r\nContent-Length: 30\r\n\r\n");
        assertTrue(twice.startsWith("HTTP/1.1 400"), twice);
        String signed = raw(egress.port(), target + "Content-Length: +0\r\n\r\n");
        assertTrue(signed.startsWith("HTTP/1.1 400"), signed);
        String folded = raw(egress.port(), target + " Content-Length: 30\r\n\r\n");
        assertTrue(folded.startsWith("HTTP/1.1 400"), folded);
        assertEquals(0, site.hits().get());
    }

    /**
     * 数字写法不规范的主机（八进制、十进制整数、十六进制）直接拒绝：JDK 把 {@code 0177.0.0.1} 当十进制 177.0.0.1 放行，
     * 按 inet_aton 解析的上游代理却当成 127.0.0.1，检查的和连的就不是同一个地址。
     */
    @Test
    void nonCanonicalNumericHostsAreRefused() throws Exception {
        Egress egress = egress(new AddressRules(false), UpstreamProxy.none());
        for (String host : List.of("0177.0.0.1", "2130706433", "0x7f.0.0.1", "127.1", "127.0.0.01")) {
            String tunnel = raw(egress.port(), "CONNECT " + host + ":443 HTTP/1.1\r\n\r\n");
            assertTrue(tunnel.startsWith("HTTP/1.1 400"), host + ": " + tunnel);
            String plain = raw(egress.port(), "GET http://" + host + "/ HTTP/1.1\r\nHost: " + host + "\r\n\r\n");
            assertTrue(plain.startsWith("HTTP/1.1 400"), host + ": " + plain);
        }
        Site site = site("ok");
        String canonical = raw(egress.port(), "GET http://127.0.0.1:" + site.port() + "/ HTTP/1.1\r\nHost: x\r\n\r\n");
        assertTrue(canonical.startsWith("HTTP/1.1 200"), canonical);
    }

    /** 网站自己发的 {@code X-Tantalum-Blocked} 头被去掉，冒充不了出口。 */
    @Test
    void sitesCannotForgeTheBlockedHeader() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().add(Egress.BLOCKED_HEADER, "own-port");
            exchange.sendResponseHeaders(403, -1);
            exchange.close();
        });
        server.start();
        cleanup.add(() -> server.stop(0));
        Egress egress = egress(new AddressRules(false), UpstreamProxy.none());
        HttpResponse<String> response = egress.client().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(403, response.statusCode());
        assertTrue(response.headers().firstValue(Egress.BLOCKED_HEADER).isEmpty());
    }

    /** W12：假的上游代理收到的 CONNECT 主机正确、带 {@code Proxy-Authorization}；明文请求转成绝对网址，客户端自己带的代理凭据去掉。 */
    @Test
    void upstreamProxyGetsTheTargetAndCredentials() throws Exception {
        List<String> heads = new CopyOnWriteArrayList<>();
        ServerSocket fake = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        cleanup.add(fake);
        Thread.ofVirtual().start(() -> {
            while (true) {
                try {
                    Socket socket = fake.accept();
                    Thread.ofVirtual().start(() -> fakeProxy(socket, heads));
                } catch (IOException closed) {
                    return;
                }
            }
        });
        String env = "http://alice:s3cret@127.0.0.1:" + fake.getLocalPort();
        UpstreamProxy upstream = UpstreamProxy.fromEnvironment(Map.of("HTTPS_PROXY", env, "HTTP_PROXY", env)::get);
        Egress egress = egress(new AddressRules(false), upstream);
        String basic = "Basic " + Base64.getEncoder().encodeToString("alice:s3cret".getBytes(StandardCharsets.UTF_8));

        // 192.0.2.1 是文档专用地址（TEST-NET-1），不查 DNS；假代理不会真的去连它。
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), egress.port())) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write("CONNECT 192.0.2.1:443 HTTP/1.1\r\nHost: 192.0.2.1:443\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            InputStream in = socket.getInputStream();
            String reply = head(in);
            assertTrue(reply.startsWith("HTTP/1.1 200"), reply);
            socket.getOutputStream().write("ping".getBytes(StandardCharsets.ISO_8859_1));
            assertEquals("ping", new String(in.readNBytes(4), StandardCharsets.ISO_8859_1));
        }
        assertTrue(heads.getFirst().startsWith("CONNECT 192.0.2.1:443 HTTP/1.1"), heads.getFirst());
        assertTrue(heads.getFirst().contains("Proxy-Authorization: " + basic), heads.getFirst());

        String plain = raw(egress.port(), "GET http://192.0.2.1/page?q=1 HTTP/1.1\r\nHost: 192.0.2.1\r\n"
                + "Proxy-Authorization: Basic Zm9yZ2Vk\r\nProxy-Connection: keep-alive\r\n\r\n");
        assertTrue(plain.startsWith("HTTP/1.1 200"), plain);
        String forwarded = heads.get(1);
        assertTrue(forwarded.startsWith("GET http://192.0.2.1:80/page?q=1 HTTP/1.1"), forwarded);
        assertTrue(forwarded.contains("Proxy-Authorization: " + basic), forwarded);
        assertFalse(forwarded.contains("Zm9yZ2Vk"), forwarded);
        assertFalse(forwarded.toLowerCase().contains("proxy-connection"), forwarded);
        assertTrue(forwarded.contains("Connection: close"), forwarded);
    }

    /** 假的上游代理：记下请求头；CONNECT 回 200 后原样回显，明文请求回一个固定页面。 */
    private static void fakeProxy(Socket socket, List<String> heads) {
        try (socket) {
            InputStream in = socket.getInputStream();
            String head = head(in);
            heads.add(head);
            if (head.startsWith("CONNECT")) {
                socket.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                byte[] buffer = new byte[64];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    socket.getOutputStream().write(buffer, 0, n);
                }
            } else {
                socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".getBytes(StandardCharsets.ISO_8859_1));
            }
        } catch (IOException ignored) {
            // 测试结束时连接被关
        }
    }

    private static String head(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        while (!head.toString(StandardCharsets.ISO_8859_1).endsWith("\r\n\r\n")) {
            int b = in.read();
            if (b < 0) {
                break;
            }
            head.write(b);
        }
        return head.toString(StandardCharsets.ISO_8859_1);
    }

    @Test
    void moreThan512ConnectionsGet503() throws Exception {
        Egress egress = egress(new AddressRules(false), UpstreamProxy.none());
        List<Socket> held = new ArrayList<>();
        try {
            for (int i = 0; i < Egress.MAX_CONNECTIONS; i++) {
                held.add(new Socket(InetAddress.getLoopbackAddress(), egress.port()));
            }
            // 前 512 条都在等请求头；第 513 条马上得到 503
            String refused = raw(egress.port(), "");
            assertTrue(refused.startsWith("HTTP/1.1 503"), refused);
        } finally {
            for (Socket socket : held) {
                socket.close();
            }
        }
    }

    /** NFR Security：拦下时日志只有主机、端口、原因，没有路径和查询串。 */
    @Test
    void logsKeepHostPortAndReasonOnly() throws Exception {
        Site site = site("inside");
        Egress egress = egress(new AddressRules(true), UpstreamProxy.none());
        try (LogCapture log = new LogCapture()) {
            egress.client().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + site.port() + "/token-path-7f3a?key=hunter2")).build(),
                    HttpResponse.BodyHandlers.ofString());
            String all = log.all();
            assertTrue(all.contains("127.0.0.1:" + site.port()) && all.contains("private-network"), all);
            assertFalse(all.contains("token-path-7f3a") || all.contains("hunter2"), all);
        }
    }

    /** 一直有数据的 CONNECT 隧道也有总寿命（默认 30 分钟），到了两头一起关，名额空出来。这里寿命改成 1 秒。 */
    @Test
    void aBusyTunnelIsClosedWhenItsLifetimeIsOver() throws Exception {
        assertEquals(java.time.Duration.ofMinutes(30), Egress.TUNNEL_LIFETIME);
        ServerSocket chatty = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        cleanup.add(chatty);
        Thread.ofVirtual().start(() -> {
            try (Socket peer = chatty.accept()) {
                while (true) {
                    peer.getOutputStream().write('x');
                    peer.getOutputStream().flush();
                    Thread.sleep(50);
                }
            } catch (IOException | InterruptedException closed) {
                // 隧道关了
            }
        });
        Egress egress = Egress.start(new AddressRules(false), UpstreamProxy.none(), java.time.Duration.ofSeconds(1));
        cleanup.add(egress);
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), egress.port())) {
            socket.setSoTimeout(10_000);
            String target = "127.0.0.1:" + chatty.getLocalPort();
            socket.getOutputStream().write(("CONNECT " + target + " HTTP/1.1\r\nHost: " + target + "\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            long start = System.nanoTime();
            byte[] all = socket.getInputStream().readAllBytes();
            long millis = java.time.Duration.ofNanos(System.nanoTime() - start).toMillis();
            String text = new String(all, StandardCharsets.ISO_8859_1);
            assertTrue(text.startsWith("HTTP/1.1 200"), text);
            assertTrue(text.contains("xxxx"), "data kept flowing, so the idle limit did not close it");
            assertTrue(millis >= 900 && millis < 5_000, millis + " ms");
        }
    }

    /** 用 JDK 的 keytool 生成一张只在测试里用的自签证书。 */
    private SSLContext selfSigned() throws Exception {
        Path store = temp.resolve("test.p12");
        char[] password = "changeit".toCharArray();
        Process keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "site", "-keyalg", "EC", "-groupname", "secp256r1", "-validity", "2",
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
                "-storetype", "PKCS12", "-keystore", store.toString(), "-storepass", "changeit")
                .redirectErrorStream(true).start();
        String output = new String(keytool.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, keytool.waitFor(), output);
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream in = java.nio.file.Files.newInputStream(store)) {
            keys.load(in, password);
        }
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keys, password);
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(keys);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers.getKeyManagers(), trust.getTrustManagers(), null);
        return context;
    }

    /** review-fixes 需求 11.8：读回复头时超时只是轮询，已经读到的字节不能丢；请求头有总时限。 */
    @Test
    void theHeadReaderKeepsWhatItReadAcrossTimeoutsAndHasADeadline() throws Exception {
        byte[] bytes = "HTTP/1.1 200 OK\r\nX-A: 1\r\n\r\nbody".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        java.io.InputStream flaky = new java.io.InputStream() {
            int calls;
            int at;
            @Override public int read() throws java.io.IOException {
                // 第 10 次和第 20 次读超时，字节一个不少
                if (++calls == 10 || calls == 20) {
                    throw new java.net.SocketTimeoutException("poll");
                }
                return at < bytes.length ? bytes[at++] & 0xff : -1;
            }
        };
        Egress.HeadReader reader = new Egress.HeadReader(1024, Long.MAX_VALUE);
        String head = null;
        int timeouts = 0;
        while (head == null) {
            try {
                head = reader.read(flaky);
            } catch (java.net.SocketTimeoutException quiet) {
                timeouts++;
            }
        }
        assertEquals(2, timeouts);
        assertEquals("HTTP/1.1 200 OK\r\nX-A: 1", head);

        java.io.InputStream slow = new java.io.InputStream() {
            @Override public int read() throws java.io.IOException {
                try {
                    Thread.sleep(30);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return 'x';
            }
        };
        assertThrows(java.net.SocketTimeoutException.class, () -> new Egress.HeadReader(1024, 100).read(slow), "one byte every 30 ms: 100 ms total is up");
    }
}
