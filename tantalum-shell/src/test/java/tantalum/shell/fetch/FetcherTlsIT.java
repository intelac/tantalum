package tantalum.shell.fetch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.apache.catalina.Context;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.apache.coyote.http2.Http2Protocol;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.fetch.Fetcher.FetchFailed;
import tantalum.shell.fetch.Fetcher.Fetched;
import tantalum.shell.net.AddressRules;
import tantalum.shell.net.Egress;
import tantalum.shell.net.UpstreamProxy;

/**
 * {@code web_fetch} 走 https（经出口的 CONNECT 隧道，TLS 里协商成 HTTP/2）时的跳转、不收的类型和 5 MB 截断。
 * 2026-11-07 8091 的真实演示里，所有回跳转的 https 网址都报 “the connection failed”：跳转不读正文，订阅一开始就取消，
 * HTTP/2 上 JDK 把取消当成整个回复失败（“Stream 1 cancelled”）；明文 http（HTTP/1.1）的测试没有暴露。
 * <p>本地内嵌 Tomcat（TLS + h2，自签名证书，只信任这一张）。测试期间把这张证书设成 JVM 默认的 {@link SSLContext}，
 * 出口的 {@code HttpClient} 在创建时取用它；生产代码的信任设置不变。不访问外网。
 */
class FetcherTlsIT {

    @TempDir
    static Path temp;

    private static Tomcat tomcat;
    private static SSLContext previous;
    private static Egress egress;
    private static Fetcher fetcher;
    private static int port;
    private static final Set<String> protocols = ConcurrentHashMap.newKeySet();

    @BeforeAll
    static void start() throws Exception {
        Path keystore = temp.resolve("tls.p12");
        Process keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "site", "-keyalg", "EC", "-groupname", "secp256r1", "-validity", "2",
                "-dname", "CN=localhost", "-ext", "san=dns:localhost,ip:127.0.0.1", "-storetype", "PKCS12",
                "-keystore", keystore.toString(), "-storepass", "changeit", "-keypass", "changeit").redirectErrorStream(true).start();
        String output = new String(keytool.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, keytool.waitFor(), output);

        tomcat = new Tomcat();
        tomcat.setBaseDir(Files.createDirectories(temp.resolve("tomcat")).toString());
        Connector connector = new Connector("org.apache.coyote.http11.Http11NioProtocol");
        connector.setPort(0);
        connector.setProperty("address", "127.0.0.1");
        connector.setSecure(true);
        connector.setScheme("https");
        connector.setProperty("SSLEnabled", "true");
        SSLHostConfig ssl = new SSLHostConfig();
        SSLHostConfigCertificate certificate = new SSLHostConfigCertificate(ssl, SSLHostConfigCertificate.Type.EC);
        certificate.setCertificateKeystoreFile(keystore.toString());
        certificate.setCertificateKeystorePassword("changeit");
        certificate.setCertificateKeystoreType("PKCS12");
        ssl.addCertificate(certificate);
        connector.addSslHostConfig(ssl);
        connector.addUpgradeProtocol(new Http2Protocol());
        tomcat.setConnector(connector);
        Context context = tomcat.addContext("", null);
        Tomcat.addServlet(context, "site", new HttpServlet() {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
                protocols.add(request.getProtocol());
                String path = request.getRequestURI();
                switch (path) {
                    case "/news" -> redirect(response, 302, "/news/");
                    case "/search" -> redirect(response, 303, "/search.html");
                    case "/elsewhere" -> redirect(response, 302, "https://localhost:" + port + "/news/");
                    case "/picture" -> {
                        response.setContentType("image/png");
                        response.getOutputStream().write(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, 0, 0, 0});
                    }
                    case "/big" -> {
                        response.setContentType("text/plain");
                        byte[] chunk = "a".repeat(64 * 1024).getBytes(StandardCharsets.US_ASCII);
                        try (OutputStream out = response.getOutputStream()) {
                            for (int i = 0; i < 96; i++) {
                                out.write(chunk);
                            }
                        } catch (IOException stopped) {
                            // 读的一方在 5 MB 处取消了
                        }
                    }
                    default -> {
                        response.setContentType("text/html; charset=utf-8");
                        response.getWriter().write("<html><body><h1>Landed</h1><p>at " + path + "</p></body></html>");
                    }
                }
            }
        });
        context.addServletMappingDecoded("/*", "site");
        tomcat.start();
        port = connector.getLocalPort();

        KeyStore trusted = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            trusted.load(in, "changeit".toCharArray());
        }
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(trusted);
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(null, trust.getTrustManagers(), null);
        previous = SSLContext.getDefault();
        SSLContext.setDefault(tls);
        AddressRules rules = new AddressRules(false);
        egress = Egress.start(rules, UpstreamProxy.none());
        fetcher = new Fetcher(egress, rules);
        SSLContext.setDefault(previous);
    }

    @AfterAll
    static void stop() throws Exception {
        if (egress != null) {
            egress.close();
        }
        tomcat.stop();
        tomcat.destroy();
    }

    private static void redirect(HttpServletResponse response, int status, String location) {
        response.setStatus(status);
        response.setHeader("Location", location);
        response.setContentType("text/html");
    }

    private static URI url(String path) {
        return URI.create("https://127.0.0.1:" + port + path);
    }

    /** 实测漏洞的回归：同一主机的 302、303，和跳到另一个主机名（新的 CONNECT），都跟到最后一页。 */
    @Test
    void httpsRedirectsAreFollowedOverHttp2() throws Exception {
        Fetched news = fetcher.fetch(url("/news"));
        assertEquals(url("/news/"), news.finalUrl());
        assertEquals(200, news.status());
        assertTrue(news.text().contains("at /news/"), news.text());

        Fetched search = fetcher.fetch(url("/search"));
        assertEquals(url("/search.html"), search.finalUrl());

        Fetched elsewhere = fetcher.fetch(url("/elsewhere"));
        assertEquals(URI.create("https://localhost:" + port + "/news/"), elsewhere.finalUrl());
        assertTrue(elsewhere.text().contains("# Landed"), elsewhere.text());
        assertEquals(Set.of("HTTP/2.0"), protocols, "the site must really speak HTTP/2, where the bug was");
    }

    /** 不读正文的另一种情况：不收的类型给出原来的提示，不是“连接失败”。 */
    @Test
    void aRefusedTypeOverHttpsGetsItsOwnMessage() {
        FetchFailed refused = org.junit.jupiter.api.Assertions.assertThrows(FetchFailed.class, () -> fetcher.fetch(url("/picture")));
        assertTrue(refused.getMessage().startsWith("This is an image (image/png)."), refused.getMessage());
    }

    /** 读满 5 MB 时也是取消：截断后照常返回。 */
    @Test
    void aLargePageOverHttpsStopsAtFiveMegabytes() throws Exception {
        Fetched big = fetcher.fetch(url("/big"));
        assertTrue(big.cutAt5Mb());
        assertEquals(Fetcher.MAX_BYTES, big.text().length());
    }
}
