package tantalum.shell.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tantalum.shell.net.AddressRules;
import tantalum.shell.net.Egress;
import tantalum.shell.net.UpstreamProxy;
import tantalum.value.id.SessionId;

/**
 * 任务 16 的最小用例（W7、需求 2.1、2.3、2.8）：真实 Chromium 经真实出口打开本地页，下载被拒，关掉后没有子进程。
 * 没装 Chromium headless shell 时跳过并写明原因。完整的用例在任务 20 的 {@code BrowsersIT}。
 */
@Timeout(120)
class UserBrowserIT {

    /**
     * 所有浏览器 IT 共用的驱动目录：Playwright 在一个进程里只认第一次用到的驱动目录（{@code Driver} 是单例），
     * 每个测试一个临时目录的话，第一个测试删掉目录后，后面的测试就起不来驱动。
     */
    static final Path DRIVER_DATA = Path.of("target", "it-browser-data").toAbsolutePath();

    private static List<ProcessHandle> browserProcesses() {
        return ProcessHandle.current().descendants()
                .filter(process -> process.info().command().map(command -> command.contains("chrome") || command.endsWith("node")
                        || command.endsWith("node.exe"))
                        .orElse(false))
                .toList();
    }

    @Test
    void opensALocalPageThroughTheEgressRefusesDownloadsAndLeavesNoProcess() throws Exception {
        HttpServer site = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        AtomicInteger hits = new AtomicInteger();
        site.createContext("/", exchange -> {
            hits.incrementAndGet();
            boolean download = exchange.getRequestURI().getPath().equals("/file.bin");
            byte[] body = (download ? "binary" : "<html><head><title>hello local</title></head><body><button>Go</button></body></html>")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", download ? "application/octet-stream" : "text/html; charset=utf-8");
            if (download) {
                exchange.getResponseHeaders().add("Content-Disposition", "attachment; filename=file.bin");
            }
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        site.start();
        AddressRules rules = new AddressRules(false);
        try (Egress egress = Egress.start(rules, UpstreamProxy.none())) {
            UserBrowser browser;
            try {
                browser = UserBrowser.open(new BrowserDriver(DRIVER_DATA), egress.port(), true, Clock.systemUTC());
            } catch (Browsers.Unavailable unavailable) {
                assumeTrue(!unavailable.getMessage().equals(UserBrowser.NOT_INSTALLED),
                        "Chromium headless shell is not installed here; run: java -jar tantalum.jar browser install");
                throw unavailable;
            }
            String url = "http://127.0.0.1:" + site.getAddress().getPort();
            browser.lock.lock();
            try {
                assertTrue(!browserProcesses().isEmpty(), "the driver and Chromium run as child processes");
                Page page = browser.page(new SessionId("s1"));
                page.navigate(url + "/");
                assertEquals("hello local", page.title());
                assertTrue(page.ariaSnapshot(new Page.AriaSnapshotOptions()
                        .setMode(com.microsoft.playwright.options.AriaSnapshotMode.AI)).contains("[ref="));
                PlaywrightException download = assertThrows(PlaywrightException.class, () -> page.navigate(url + "/file.bin"));
                assertTrue(download.getMessage().contains("Download is starting"), download.getMessage());
                assertTrue(browser.connected());
            } finally {
                browser.close();
                browser.lock.unlock();
            }
            assertTrue(hits.get() >= 2);
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (!browserProcesses().isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(100);
            }
            assertEquals(List.of(), browserProcesses().stream().map(ProcessHandle::pid).toList(), "no driver or Chromium left");
        } finally {
            site.stop(0);
        }
    }
}
