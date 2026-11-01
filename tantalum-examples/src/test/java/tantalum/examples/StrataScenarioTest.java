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

    /** 需求 5.2：首条请求后读超大文件，头部很小。判定：运行完成，答对文件首尾的暗号，发生过压缩。 */
    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void hugeFileAfterTheFirstRequestRecovers() throws Exception {
        Path workspace = temp.resolve("workspace"); Files.createDirectories(workspace.resolve("huge"));
        CompactionScenario.writeHugeFile(workspace.resolve("huge"));
        Path data = temp.resolve("data-huge");
        try (TestServer server = new TestServer(data, workspace, client(), CompactionScenario.SETTINGS_OPENAI)) {
            String answer = CompactionScenario.ask(server, "huge", "s1",
                    "Read huge.txt with read_file once and tell me the code on its first line and the code on its last line.");
            System.out.println("[strata huge] compactions=" + CompactionScenario.compactions(server, "huge", "s1") + " answer=" + answer.replace("\n", " | "));
            assertTrue(answer.contains(CompactionScenario.HUGE_HEAD), answer);
            assertTrue(answer.contains(CompactionScenario.HUGE_TAIL), answer);
            assertTrue(CompactionScenario.compactions(server, "huge", "s1") >= 1, "the scenario proved nothing");
        }
    }

    /** 需求 5.3：压缩至少两次，第一次压缩前的约束在结尾仍被遵守，文件列表累积。 */
    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void aConstraintSurvivesTwoCompactions() throws Exception {
        Path workspace = temp.resolve("workspace"); Files.createDirectories(workspace.resolve("twice"));
        CompactionScenario.writeNotes(workspace.resolve("twice"));
        Path data = temp.resolve("data-twice");
        try (TestServer server = new TestServer(data, workspace, client(), CompactionScenario.SETTINGS_OPENAI)) {
            CompactionScenario.ask(server, "twice", "s1", "A standing rule for this whole conversation: end every report with the exact word FINCH-END. Reply with OK.");
            CompactionScenario.ask(server, "twice", "s1", "Read note01.txt to note05.txt one at a time, then say done.");
            CompactionScenario.ask(server, "twice", "s1", "Read note06.txt to note10.txt one at a time, then say done.");
            CompactionScenario.ask(server, "twice", "s1", "Read note01.txt to note05.txt again one at a time, then say done.");
            String answer = CompactionScenario.ask(server, "twice", "s1", "Write a one-line report of what you did. Do not read anything.");
            int compactions = CompactionScenario.compactions(server, "twice", "s1");
            System.out.println("[strata twice] compactions=" + compactions + " answer=" + answer.replace("\n", " | "));
            assertTrue(compactions >= 2, "only " + compactions + " compactions");
            assertTrue(answer.contains("FINCH-END"), answer);
        }
    }

    private static OpenAiClient client() {
        return new OpenAiClient(BASE + "/v1", MODEL, null, 16384, Duration.ofSeconds(600), Duration.ofSeconds(120), OverflowRules.defaults());
    }
}
