package tantalum.shell.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 向量服务的客户端（memory-final-plan 第 9 节）：没配置时关着；请求是 OpenAI 兼容的样子，问句带上配置的说明；
 * 回答按 index 排回原来的顺序、截到配置的维数、归一化；个数对不上、服务出错都返回空而不是抛出。
 */
class EmbeddingsTest {

    @Test
    void offUnlessAUrlAndAModelAreConfigured() {
        assertFalse(new Embeddings(Embeddings.Settings.OFF).on());
        assertFalse(new Embeddings(new Embeddings.Settings("http://127.0.0.1:1/v1", " ", 0, null, null)).on());
        assertTrue(new Embeddings(Embeddings.Settings.OFF).embed(List.of("x"), false).isEmpty());
    }

    @Test
    void theAnswerIsPutBackInOrderCutToTheConfiguredSizeAndNormalised() {
        String body = "{\"data\":[{\"index\":1,\"embedding\":[0,2,9]},{\"index\":0,\"embedding\":[3,4,9]}]}";
        List<float[]> vectors = Embeddings.parse(body, 2, 2).orElseThrow();
        assertEquals(2, vectors.getFirst().length);
        assertEquals(0.6f, vectors.getFirst()[0], 1e-6);
        assertEquals(0.8f, vectors.getFirst()[1], 1e-6);
        assertEquals(1.0f, vectors.get(1)[1], 1e-6);
        assertEquals(3, Embeddings.parse(body, 2, 0).orElseThrow().getFirst().length, "0 keeps every dimension");
        assertTrue(Embeddings.parse(body, 3, 0).isEmpty(), "one vector per text, or nothing");
        assertTrue(Embeddings.parse("{\"data\":[{\"index\":0,\"embedding\":[0,0]}]}", 1, 0).isEmpty(), "a zero vector cannot be compared");
        assertTrue(Embeddings.parse("{\"data\":[{\"index\":0,\"embedding\":[1]},{\"index\":0,\"embedding\":[1]}]}", 2, 0).isEmpty());
    }

    @Test
    void theRequestNamesTheModelAndOnlyQueriesCarryThePrefix() throws Exception {
        AtomicReference<String> received = new AtomicReference<>();
        AtomicReference<Integer> status = new AtomicReference<>(200);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] answer = "{\"data\":[{\"index\":0,\"embedding\":[1,0]}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        server.start();
        try {
            Embeddings embeddings = new Embeddings(new Embeddings.Settings("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/",
                    "tiny-embed", 0, "Query: ", Duration.ofSeconds(5)));
            Optional<List<float[]>> asked = embeddings.embed(List.of("my plate"), true);
            assertEquals(1, asked.orElseThrow().size());
            assertTrue(received.get().contains("\"model\":\"tiny-embed\"") && received.get().contains("\"Query: my plate\""), received.get());
            embeddings.embed(List.of("my plate"), false);
            assertTrue(received.get().contains("[\"my plate\"]"), "the text being searched carries no prefix: " + received.get());

            status.set(500);
            assertTrue(embeddings.embed(List.of("my plate"), false).isEmpty(), "a failing service gives nothing, it does not throw");
            assertTrue(embeddings.embed(java.util.Collections.nCopies(Embeddings.MAX_BATCH + 1, "x"), false).isEmpty());
        } finally {
            server.stop(0);
        }
    }
}
