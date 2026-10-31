package tantalum.shell.spike;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClaudeSdkSpikeTest {

    private static final String MESSAGE = """
            {"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-5-5",
             "content":[{"type":"text","text":"hi"}],"stop_reason":"end_turn","stop_sequence":null,
             "usage":{"input_tokens":3,"output_tokens":1}}""";

    private static final String STREAM = """
            event: message_start
            data: {"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-5-5","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":3,"output_tokens":1}}}

            event: ping
            data: {"type":"ping"}

            event: message_stop
            data: {"type":"message_stop"}

            """;

    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();
    private volatile int status = 200;
    private volatile String contentType = "application/json";
    private volatile String body = MESSAGE;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("content-type", contentType);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private AnthropicClient client() {
        return AnthropicOkHttpClient.builder()
                .apiKey("test-key")
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .maxRetries(0)
                .build();
    }

    private static MessageCreateParams params() {
        return MessageCreateParams.builder()
                .model("claude-sonnet-5-5")
                .maxTokens(16)
                .addUserMessage("hi")
                .build();
    }

    @Test
    void parsesAMessageAgainstTheManagedJacksonVersion() {
        Message message = client().messages().create(params());
        assertEquals("msg_1", message.id());
    }

    @Test
    void readsAStreamIncludingPing() {
        contentType = "text/event-stream";
        body = STREAM;
        try (StreamResponse<RawMessageStreamEvent> stream = client().messages().createStreaming(params())) {
            assertEquals(2, stream.stream().count());
        }
    }

    @Test
    void doesNotRetryWhenRetriesAreOff() {
        status = 500;
        body = "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"boom\"}}";
        assertThrows(AnthropicServiceException.class, () -> client().messages().create(params()));
        assertEquals(1, hits.get());
    }
}
