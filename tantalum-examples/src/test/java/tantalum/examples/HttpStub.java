package tantalum.examples;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;

/** 本地 HTTP 桩，只监听 127.0.0.1 的随机端口。按第几次请求和请求体决定回复，记下收到的请求体。 */
final class HttpStub implements AutoCloseable {

    record Reply(int status, String body) {}

    private final HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();

    HttpStub(BiFunction<Integer, String, Reply> reply) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(body);
            Reply answer = reply.apply(requests.size(), body);
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

    /** 一个刚关掉的端口：连接会被拒绝。 */
    static URI closedPort() throws IOException {
        HttpStub stub = new HttpStub((n, body) -> new Reply(200, ""));
        URI uri = stub.uri();
        stub.close();
        return uri;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
