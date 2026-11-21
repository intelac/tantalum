package tantalum.shell.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.cli.CliText;
import tantalum.shell.model.ModelClient;
import tantalum.shell.model.ModelOutcome;
import tantalum.shell.web.TestServer;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tantalum.value.model.TokenUsage;
import tantalum.value.text.Language;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 折算 token 的整条链路（qagent-align 需求 3.2–3.6）：配了系数，模型回复的用量折算后和四项一起进会话文件；今日用量用折算后的数；
 * 之后换一套系数，已经记下的不变，重放和记下的一致。没配系数时和以前一个字不差。
 */
class WeightedUsageTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 每次调用：输入 100、输出 20、缓存读 300、缓存写 40，四项之和 460。 */
    private static final TokenUsage EACH = new TokenUsage(100, 20, 300, 40);
    private static final String[] HEAVY = {"--tantalum.usage-weights.claude.output=5", "--tantalum.usage-weights.claude.cache-read=0.1"};

    @TempDir
    Path temp;

    private final ModelClient model = (prompt, chunks) ->
            new ModelOutcome.Replied(List.of(new TextBlock("ok")), StopReason.END_TURN, "m", EACH);

    private TestServer server(String... extra) throws Exception {
        Files.createDirectories(temp.resolve("ws"));
        return new TestServer(temp.resolve("data"), temp.resolve("ws"), model, extra);
    }

    private static void ask(TestServer server, String session, int run) throws Exception {
        String body = "{\"requestId\":\"" + session + "-" + run + "\",\"delivery\":\"queue\",\"text\":\"hi\"}";
        assertEquals(200, server.post("alice", "/api/sessions/" + session + "/submissions", body).statusCode());
        for (int i = 0; i < 1000; i++) {
            JsonNode runs = JSON.readTree(server.get("alice", "/api/sessions/" + session + "/runs").body()).get("runs");
            if (runs != null && runs.size() == run && runs.get(run - 1).has("endReason")) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("run " + run + " did not end");
    }

    private static JsonNode today(TestServer server) throws Exception {
        return JSON.readTree(server.get("alice", "/api/usage/today").body());
    }

    /** 今日用量从索引读，索引成批写：等它到 {@code expected}（到不了就把最后读到的交给断言）。 */
    private static JsonNode today(TestServer server, long expected) throws Exception {
        JsonNode today = today(server);
        for (int i = 0; i < 800 && today.get("used").asLong() != expected; i++) {
            Thread.sleep(10);
            today = today(server);
        }
        assertEquals(expected, today.get("used").asLong(), today.toString());
        return today;
    }

    private String journal(String session) throws Exception {
        return Files.readString(temp.resolve("data/sessions/alice/" + session + ".jsonl"));
    }

    private int check(String... extra) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String[] args = java.util.stream.Stream.concat(java.util.stream.Stream.of("--tantalum.data-dir=" + temp.resolve("data"),
                "--tantalum.workspace=" + temp.resolve("ws")), java.util.stream.Stream.of(extra)).toArray(String[]::new);
        int code = RecordCheckCommand.run(args, new PrintStream(out, true, StandardCharsets.UTF_8), CliText.of(Language.EN));
        assertEquals(0, code, out.toString(StandardCharsets.UTF_8));
        return code;
    }

    @Test
    void theWeighedAmountIsRecordedWithTheCallAndCountedForToday() throws Exception {
        try (TestServer server = server(HEAVY)) {
            ask(server, "s1", 1);
            // 100*1 + 20*5 + 300*0.1 + 40*1 = 270
            JsonNode today = today(server, 270);
            assertTrue(today.get("weighted").asBoolean());
            assertEquals(100, today.get("input").asLong(), "the four raw numbers are still there");
            assertEquals(20, today.get("output").asLong());
            assertEquals(300, today.get("cacheRead").asLong());
            assertEquals(40, today.get("cacheWrite").asLong());
            ask(server, "s1", 2);
            today(server, 540);
        }
        String journal = journal("s1");
        assertTrue(journal.contains("\"usage\":{\"input\":100,\"output\":20,\"cacheRead\":300,\"cacheWrite\":40,\"weighted\":270}"), journal);
        check(HEAVY);
    }

    /** 需求 3.5：换一套系数重启，已经记下的数不变（索引删掉从文件重建也一样），新的调用按新系数；重放不看现在的配置。 */
    @Test
    void changingTheWeightsLaterLeavesWhatWasRecorded() throws Exception {
        try (TestServer server = server(HEAVY)) {
            ask(server, "s1", 1);
        }
        Files.delete(temp.resolve("data/index.db"));
        String[] light = {"--tantalum.usage-weights.claude.input=0", "--tantalum.usage-weights.claude.output=0.5",
                "--tantalum.usage-weights.claude.cache-read=0", "--tantalum.usage-weights.claude.cache-write=0"};
        try (TestServer server = server(light)) {
            // 索引是从会话文件重建的，用的是记下的数，不是今天的系数
            today(server, 270);
            ask(server, "s1", 2);
            // 新的一次：20 * 0.5 = 10
            today(server, 280);
        }
        check(light);
        check();
        try (TestServer server = server()) {
            // 系数去掉了：记下的数还在
            JsonNode today = today(server, 280);
            assertFalse(today.has("weighted"), "no weights configured now: the page does not say weighted");
            ask(server, "s1", 3);
            // 没折算的一次算四项之和
            today(server, 740);
        }
    }

    /** 需求 3.6：没配系数的部署，写出来的文件里没有折算这一项，接口也不提。 */
    @Test
    void withoutWeightsNothingChanges() throws Exception {
        try (TestServer server = server()) {
            ask(server, "s1", 1);
            assertFalse(today(server, 460).has("weighted"));
        }
        assertFalse(journal("s1").contains("weighted"));
        assertTrue(journal("s1").contains("\"usage\":{\"input\":100,\"output\":20,\"cacheRead\":300,\"cacheWrite\":40}"));
        check();
    }

    /** 全是 1 的系数：和没配一样，文件里不写。 */
    @Test
    void weightsOfOneAreTheSameAsNone() throws Exception {
        try (TestServer server = server("--tantalum.usage-weights.claude.output=1")) {
            ask(server, "s1", 1);
            assertFalse(today(server, 460).has("weighted"));
        }
        assertFalse(journal("s1").contains("weighted"));
    }
}
