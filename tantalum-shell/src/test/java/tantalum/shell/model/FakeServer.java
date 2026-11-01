package tantalum.shell.model;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** 本地替身 HTTP 服务，不访问外网。按设定的状态码和内容返回，可以停在中途。 */
final class FakeServer implements AutoCloseable {

    final List<String> requests = new CopyOnWriteArrayList<>();
    final AtomicInteger hits = new AtomicInteger();
    final CountDownLatch disconnected = new CountDownLatch(1);
    volatile int status = 200;
    volatile String contentType = "text/event-stream";
    volatile String body = "";
    volatile boolean hang;
    /** 接受连接但不回响应头，模拟卡死的上游。 */
    volatile boolean hangBeforeHeaders;
    /** 挂起期间是否发保活注释。发的话才能察觉客户端断开。 */
    volatile boolean keepAlive = true;
    volatile Map<String, String> headers = Map.of();
    private final CountDownLatch release = new CountDownLatch(1);
    private final HttpServer server;

    FakeServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (hangBeforeHeaders) {
                try {
                    release.await();
                } catch (InterruptedException stopping) {
                    Thread.currentThread().interrupt();
                }
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().set("content-type", contentType);
            headers.forEach(exchange.getResponseHeaders()::set);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, hang ? 0 : bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
                out.flush();
                if (hang) {
                    while (release.getCount() > 0) {
                        Thread.sleep(10);
                        if (keepAlive) {
                            out.write(": ping\n\n".getBytes(StandardCharsets.UTF_8));
                            out.flush();
                        }
                    }
                }
            } catch (IOException gone) {
                disconnected.countDown();
            } catch (InterruptedException stopping) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        release.countDown();
        server.stop(0);
    }
}
