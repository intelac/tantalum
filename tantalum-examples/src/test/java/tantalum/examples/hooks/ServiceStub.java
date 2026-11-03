package tantalum.examples.hooks;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * 外部服务的本地桩（审批服务、提供方状态页），只监听 127.0.0.1 的随机端口，按请求体决定回复，记下收到的请求体。
 * 和 {@code tantalum.examples.HttpStub} 一样，只是那个类不对别的包公开；这里多了“先等一会儿再回”，用来让工作超时。
 */
final class ServiceStub implements AutoCloseable {

    record Reply(int status, String body, long delayMillis) {

        static Reply of(int status, String body) {
            return new Reply(status, body, 0);
        }
    }

    private final HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();

    ServiceStub(Function<String, Reply> reply) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(body);
            Reply answer = reply.apply(body);
            if (answer.delayMillis() > 0) {
                try {
                    Thread.sleep(answer.delayMillis());
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] bytes = answer.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(answer.status(), bytes.length == 0 ? -1 : bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    URI uri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    List<String> requests() {
        return List.copyOf(requests);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
