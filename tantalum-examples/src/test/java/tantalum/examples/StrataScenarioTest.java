package tantalum.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.model.OpenAiClient;
import tantalum.shell.model.OverflowRules;
import tantalum.shell.web.TestServer;

/**
 * 本地 strata（qwen）跑与 Claude 相同的压缩场景：压缩是通用逻辑，两个模型用同一个用例。
 * 设置 {@code TANTALUM_SMOKE_STRATA} 才运行；strata 不可用时跳过，记为未验证，不算通过。
 */
@EnabledIfEnvironmentVariable(named = "TANTALUM_SMOKE_STRATA", matches = ".+")
class StrataScenarioTest {

    private static final String BASE = "http://127.0.0.1:8090";
    private static final String MODEL = "qwen3.8-flash-next-unsloth-ud-q4_k_xl";

    @TempDir
    Path temp;

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void compactionKeepsEveryCodeAndRuleAcrossTheSummary() throws Exception {
        Path workspace = temp.resolve("workspace");
        Files.createDirectories(workspace.resolve("notes"));
        CompactionScenario.writeNotes(workspace.resolve("notes"));
        Path data = temp.resolve("data");
        OpenAiClient client = new OpenAiClient(BASE + "/v1", MODEL, null, 16384, Duration.ofSeconds(600), Duration.ofSeconds(120),
                OverflowRules.defaults());
        CompactionScenario.Verdict verdict;
        try (TestServer server = new TestServer(data, workspace, client, CompactionScenario.SETTINGS_OPENAI)) {
            verdict = CompactionScenario.run(server, "notes", "s1", data, "strata");
        }
        assertEquals("COMPLETED", verdict.endReason());
        assertTrue(verdict.compactions() >= 1, "the context never crossed the threshold; the scenario proved nothing");
        assertEquals(Set.of(), verdict.missingCodes(), "codes lost in the summary; answer: " + verdict.answer());
        assertEquals(Set.of(), verdict.missingTags(), "rules lost in the summary; answer: " + verdict.answer());
        assertTrue(verdict.rereads() <= 1, "the model went back to the files " + verdict.rereads() + " times after compaction");
    }
}
