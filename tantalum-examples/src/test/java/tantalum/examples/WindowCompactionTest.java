package tantalum.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.model.ClaudeClient;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.OpenAiClient;
import tantalum.shell.model.OverflowRules;
import tantalum.shell.store.SessionStore;
import tantalum.shell.web.TestServer;
import tantalum.value.event.ContextAssembled;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.ModelFailed;
import tantalum.value.event.ModelReplied;
import tantalum.value.model.FailureReason;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * compaction-threshold 的真实模型测试：窗口从模型读，用户选 50%，在大窗口下按选的点压缩。
 * 每个文件是一行行 sha256 十六进制（Claude 实测每 token 约 1.5 字节，qwen 约 1.1），最后一行是暗号。第一次提问读前一半文件，
 * 没到压缩点；第二次读后一半，读完、调模型前越过压缩点。
 * 判定：窗口来源是模型；第一次提问不压缩；第二次压缩成功；没有上下文超长；运行正常结束；记下的压缩点是 50%。
 * 风险点是 1M 窗口下的第一次真实压缩（头部比 200K 时大五倍）；花费打印在输出里，记进实现日志。
 */
class WindowCompactionTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String USER = "alice";

    @TempDir
    Path temp;

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    @EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
    @EnabledIfEnvironmentVariable(named = "TANTALUM_SMOKE_CLAUDE", matches = ".+")
    void claudeAtHalfOfOneMillion() throws Exception {
        ClaudeClient claude = new ClaudeClient(AnthropicOkHttpClient.builder().apiKey(System.getenv("ANTHROPIC_API_KEY")).maxRetries(0).build(),
                new ClaudeClient.Settings("claude-sonnet-5-5", 8192, "low", true, Duration.ofSeconds(300), Duration.ofSeconds(300),
                        OverflowRules.defaults(), true));
        // 每个约 48 KB、3.2 万 token：一次 8 个约 26 万，两次 52 万，越过 50 万
        scenario(claude, "claude", 1_000_000, 500_000, 8, 750, "--tantalum.claude.max-tokens=8192");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    @EnabledIfEnvironmentVariable(named = "TANTALUM_SMOKE_STRATA", matches = ".+")
    void localQwenAtHalfOfItsWindow() throws Exception {
        OpenAiClient qwen = new OpenAiClient("http://127.0.0.1:8090/v1", "qwen3.8-flash-next-unsloth-ud-q4_k_xl", null, 16384,
                Duration.ofSeconds(600), Duration.ofSeconds(120), OverflowRules.defaults());
        // 每个约 16 KB、1.4 万 token：一次 3 个约 4.3 万，两次 8.6 万，越过 65536
        scenario(qwen, "qwen", 131_072, 65_536, 3, 250, "--tantalum.provider=openai");
    }

    private void scenario(ModelClient model, String label, long window, long point, int filesPerQuestion, int lines, String... settings)
            throws Exception {
        Path workspace = Files.createDirectories(temp.resolve("ws"));
        Path files = Files.createDirectories(workspace.resolve(USER));
        int count = filesPerQuestion * 2;
        for (int n = 1; n <= count; n++) {
            Files.writeString(files.resolve(name(n)), hexFile(n, lines));
        }
        Path data = temp.resolve("data");
        try (TestServer server = new TestServer(data, workspace, model, settings)) {
            JsonNode config = JSON.readTree(server.get(USER, "/api/config").body());
            assertEquals(window, config.get("contextWindow").asLong(), config.toString());
            assertEquals("model", config.get("windowSource").asString());
            HttpResponse<String> chosen = server.http.send(server.request(USER, "/api/settings/compaction")
                    .header("content-type", "application/json").PUT(HttpRequest.BodyPublishers.ofString("{\"percent\":50}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, chosen.statusCode(), chosen.body());

            String first = CompactionScenario.ask(server, USER, "big", question(1, filesPerQuestion));
            assertEquals(0, CompactionScenario.compactions(server, USER, "big"), "the first half stays below the point");
            String second = CompactionScenario.ask(server, USER, "big", question(filesPerQuestion + 1, count));
            int compactions = CompactionScenario.compactions(server, USER, "big");

            List<EventEnvelope> journal = new SessionStore(data, EventCodec.withBuiltins()).readAll();
            List<ModelReplied> replies = journal.stream().map(EventEnvelope::event).filter(ModelReplied.class::isInstance)
                    .map(ModelReplied.class::cast).toList();
            long overflows = journal.stream().map(EventEnvelope::event).filter(ModelFailed.class::isInstance).map(ModelFailed.class::cast)
                    .filter(failure -> failure.reason() == FailureReason.CONTEXT_OVERFLOW).count();
            List<Long> points = journal.stream().map(EventEnvelope::event).filter(ContextAssembled.class::isInstance)
                    .map(event -> ((ContextAssembled) event).compaction().threshold()).distinct().toList();
            long found = 0;
            for (int n = filesPerQuestion + 1; n <= count; n++) {
                found += second.contains(code(n)) ? 1 : 0;
            }
            System.out.println("[" + label + " window compaction] compactions=" + compactions + " overflows=" + overflows + " points=" + points
                    + " codesInSecondAnswer=" + found + "/" + filesPerQuestion);
            System.out.println("[" + label + " window compaction] first=" + first.replace("\n", " | "));
            System.out.println("[" + label + " window compaction] second=" + second.replace("\n", " | "));
            System.out.println(CompactionScenario.usage(label + " window compaction", replies));

            assertTrue(compactions >= 1, "the second question crossed the point and compacted");
            assertEquals(0, overflows, "the window was never exceeded");
            assertEquals(List.of(point), points, "every question used the chosen point");
        }
    }

    private static String name(int n) {
        return String.format("h%02d.txt", n);
    }

    static String code(int n) {
        return "END-" + n + "-" + (7000 + n * 31);
    }

    private static String question(int from, int to) {
        StringBuilder names = new StringBuilder();
        for (int n = from; n <= to; n++) {
            names.append(n == from ? "" : ", ").append(name(n));
        }
        return "Use read_file to read each of these files in full: " + names + ". Call read_file for all of them in one turn. "
                + "Then reply with only the last line of each file, one per line, nothing else.";
    }

    /** {@code lines} 行不同的 sha256 十六进制，最后一行是暗号。 */
    private static String hexFile(int n, int lines) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < lines; i++) {
            text.append(HexFormat.of().formatHex(sha.digest((n + ":" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8)))).append('\n');
        }
        return text.append(code(n)).append('\n').toString();
    }
}
