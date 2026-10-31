package tantalum.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.model.ChunkListener;
import tantalum.shell.model.ModelOutcome;
import tantalum.shell.model.OpenAiClient;
import tantalum.shell.model.OverflowRules;
import tantalum.value.model.Message;
import tantalum.value.model.Prompt;

/** 设置 {@code TANTALUM_SMOKE_STRATA} 才运行；未设置时跳过，不算失败。 */
@EnabledIfEnvironmentVariable(named = "TANTALUM_SMOKE_STRATA", matches = ".+")
class StrataSmokeTest {

    private static final String BASE = "http://127.0.0.1:8090";
    private static final String MODEL = "qwen3.8-flash-next-unsloth-ud-q4_k_xl";

    @TempDir
    Path temp;

    private static OpenAiClient client() {
        return new OpenAiClient(BASE + "/v1", MODEL, null, 16384, Duration.ofSeconds(300), Duration.ofSeconds(60), OverflowRules.defaults());
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void runsWithAToolAndPassesReasoningBack() throws Exception {
        SmokeRun.Timed timed = SmokeRun.run("strata", client(), temp);
        ModelOutcome.Replied first = (ModelOutcome.Replied) timed.outcomes.getFirst();
        System.out.println("[smoke strata] reasoning blocks sent back=" + first.content().stream()
                .filter(tantalum.value.model.ReasoningBlock.class::isInstance).count()
                + " usage=" + ((ModelOutcome.Replied) timed.outcomes.getLast()).usage());
        assertNotNull(((ModelOutcome.Replied) timed.outcomes.getLast()).usage(), "strata should report usage");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void disconnectStopsGeneration() throws Exception {
        OpenAiClient client = client();
        Prompt prompt = new Prompt("You are a poet.", List.of(), List.of(Message.user("Write a very long poem of at least 3000 words.")));
        ModelOutcome[] outcome = new ModelOutcome[1];
        boolean[] started = new boolean[1];
        Thread caller = Thread.ofVirtual().start(() -> outcome[0] = client.call(prompt, new ChunkListener() {
            @Override public void onText(String text) { started[0] = true; }
            @Override public void onReasoning(String text) { started[0] = true; }
        }));
        for (int i = 0; i < 6000 && !started[0]; i++) {
            Thread.sleep(50);
        }
        caller.interrupt();
        caller.join(30_000);
        assertInstanceOf(ModelOutcome.Interrupted.class, outcome[0]);

        HttpClient http = HttpClient.newHttpClient();
        HttpRequest slots = HttpRequest.newBuilder(URI.create(BASE + "/slots")).GET().build();
        long start = System.nanoTime();
        String body = "";
        for (int i = 0; i < 300; i++) {
            body = http.send(slots, HttpResponse.BodyHandlers.ofString()).body();
            if (body.contains("\"is_processing\": false") || body.contains("\"is_processing\":false")) {
                break;
            }
            Thread.sleep(100);
        }
        long millis = (System.nanoTime() - start) / 1_000_000;
        System.out.println("[smoke strata] idle " + millis + " ms after disconnect: " + body);
        assertEquals(true, body.contains("false"), body);
    }
}
