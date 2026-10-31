package tantalum.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.model.ChunkListener;
import tantalum.shell.model.ClaudeClient;
import tantalum.shell.model.ModelOutcome;
import tantalum.shell.model.OverflowRules;
import tantalum.value.json.Json;
import tantalum.value.model.FailureReason;
import tantalum.value.model.Message;
import tantalum.value.model.Prompt;
import tantalum.value.model.ReasoningBlock;
import tantalum.value.model.Role;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolResultBlock;
import tantalum.value.model.ToolSpec;

/** 调用真实 API，会产生费用。同时设置 {@code ANTHROPIC_API_KEY} 和 {@code TANTALUM_SMOKE_CLAUDE} 才运行。 */
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TANTALUM_SMOKE_CLAUDE", matches = ".+")
class ClaudeSmokeTest {

    @TempDir
    Path temp;

    private static ClaudeClient client() {
        return new ClaudeClient(AnthropicOkHttpClient.builder().apiKey(System.getenv("ANTHROPIC_API_KEY")).maxRetries(0).build(),
                "claude-sonnet-5-5", 4096, "low", true, Duration.ofSeconds(300), Duration.ofSeconds(300), OverflowRules.defaults());
    }

    private static final ChunkListener IGNORE = new ChunkListener() {
        @Override public void onText(String text) { }
        @Override public void onReasoning(String text) { }
    };

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void runsWithAToolAndPassesThinkingBack() throws Exception {
        SmokeRun.Timed timed = SmokeRun.run("claude", client(), temp);
        ModelOutcome.Replied first = (ModelOutcome.Replied) timed.outcomes.getFirst();
        ModelOutcome.Replied last = (ModelOutcome.Replied) timed.outcomes.getLast();
        System.out.println("[smoke claude] model=" + last.actualModel() + " usage=" + last.usage()
                + " thinkingBlocksInFirstReply=" + first.content().stream().filter(ReasoningBlock.class::isInstance).count());
        assertNotNull(last.usage());
    }

    /** 低 effort 时模型常常不产生 thinking 块，这里用需要推理的问题和工具轮，确认带签名的块回传后请求仍被接受。 */
    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void thinkingBlocksSurviveTheRoundTrip() {
        ClaudeClient client = new ClaudeClient(AnthropicOkHttpClient.builder().apiKey(System.getenv("ANTHROPIC_API_KEY")).maxRetries(0).build(),
                "claude-sonnet-5-5", 8192, "high", true, Duration.ofSeconds(300), Duration.ofSeconds(300), OverflowRules.defaults());
        ToolSpec lookup = new ToolSpec("lookup_price", "Look up the price of an item.",
                new Json.Obj(Map.of("type", new Json.Str("object"), "properties",
                        new Json.Obj(Map.of("item", new Json.Obj(Map.of("type", new Json.Str("string"))))))));
        Message question = Message.user("A factory has 7 machines. Machine k produces k^2 parts per hour but fails every (k+1) hours "
                + "and takes k hours to repair, during which it produces nothing. Over 1000 hours starting with all machines working, "
                + "how many parts in total? Work out the exact number first, then call lookup_price with the item part "
                + "and give the total value.");
        ModelOutcome.Replied first = (ModelOutcome.Replied) client.call(new Prompt("s", List.of(lookup), List.of(question)), IGNORE);
        long thinking = first.content().stream().filter(ReasoningBlock.class::isInstance).count();
        ToolCallBlock call = first.content().stream().filter(ToolCallBlock.class::isInstance).map(ToolCallBlock.class::cast).findFirst().orElseThrow();
        Message reply = new Message(Role.ASSISTANT, first.content(), false);
        Message result = new Message(Role.USER, List.of(new ToolResultBlock(call.id(), "3", false, null)), false);
        ModelOutcome second = client.call(new Prompt("s", List.of(lookup), List.of(question, reply, result)), IGNORE);
        System.out.println("[smoke claude] thinking blocks sent back=" + thinking + " second=" + second.getClass().getSimpleName()
                + (second instanceof ModelOutcome.Failed failed ? " " + failed.detail() : ""));
        assertInstanceOf(ModelOutcome.Replied.class, second);
        assertTrue(thinking > 0, "the model produced no thinking block, so the round trip was not exercised");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void oversizedPromptIsRecognisedAsContextOverflow() {
        Prompt huge = new Prompt("s", List.of(), List.of(Message.user("alpha 1729 beta ".repeat(500_000))));
        ModelOutcome outcome = client().call(huge, IGNORE);
        ModelOutcome.Failed failed = assertInstanceOf(ModelOutcome.Failed.class, outcome);
        System.out.println("[smoke claude] oversized: " + failed.reason() + " " + failed.detail());
        assertEquals(FailureReason.CONTEXT_OVERFLOW, failed.reason(), failed.detail());
        assertTrue(!failed.retryable());
    }
}
