package tantalum.shell.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.shell.route.Levels;
import tantalum.shell.route.ModelInfo;
import tantalum.shell.settings.CompactionPolicy.Limits;
import tantalum.shell.settings.CompactionPolicy.Point;
import tantalum.value.id.Principal;
import tantalum.value.state.CompactionSettings;

/**
 * compaction-threshold T4：压缩点 = min(W × 百分比, W − R)，需求里的两组例子。model-picker M10：W 是这次用的模型的窗口，
 * R = max(reserve-tokens, 这个模型的输出上限)。
 */
class CompactionPolicyTest {

    private static final Principal ALICE = new Principal("alice");
    private static final Principal BOB = new Principal("bob");
    private static final Limits LIMITS = new Limits(16_384, 20_000, 32_768, 4_096, 8_192);

    private static ModelInfo model(String route, String id, long window, long maxTokens) {
        return new ModelInfo(route, id, id, window, ModelInfo.Source.MODEL, maxTokens, true, Levels.NONE, true);
    }

    private static final ModelInfo SONNET = model("claude", "claude-sonnet-5-5", 1_000_000, 64_000);
    private static final ModelInfo QWEN = model("local", "qwen3.8", 131_072, 16_384);

    @Test
    void claudeAtOneMillion() throws Exception {
        try (UserSettings users = UserSettings.inMemory(Clock.systemUTC())) {
            CompactionPolicy policy = new CompactionPolicy(users, LIMITS);
            assertEquals(List.of(new Point(50, 500_000), new Point(70, 700_000), new Point(85, 850_000), new Point(100, 936_000)),
                    policy.points(SONNET));
            assertEquals(new CompactionSettings(1_000_000, 64_000, 20_000, 32_768, 4_096, 8_192, 936_000, 1.5),
                    policy.settings(ALICE, SONNET, 1.5), "nobody chose: full; the model's max tokens is the reserve");
            users.setCompactionPercent(ALICE, 50);
            assertEquals(500_000, policy.settings(ALICE, SONNET, 1.5).threshold());
            assertEquals(936_000, policy.settings(BOB, SONNET, 1.5).threshold(), "each user has their own");
            assertEquals(1.5, policy.settings(ALICE, SONNET, 1.5).bytesPerToken());
        }
    }

    @Test
    void localQwen() throws Exception {
        try (UserSettings users = UserSettings.inMemory(Clock.systemUTC())) {
            assertEquals(List.of(new Point(50, 65_536), new Point(70, 91_750), new Point(85, 111_411), new Point(100, 114_688)),
                    new CompactionPolicy(users, LIMITS).points(QWEN));
        }
    }

    /** M10：同一个用户、同一个百分比，换了模型压缩点就按新模型的窗口算。 */
    @Test
    void thePointFollowsTheChosenModel() throws Exception {
        try (UserSettings users = UserSettings.inMemory(Clock.systemUTC())) {
            CompactionPolicy policy = new CompactionPolicy(users, LIMITS);
            users.setCompactionPercent(ALICE, 70);
            assertEquals(700_000, policy.settings(ALICE, SONNET, 1.5).threshold());
            CompactionSettings qwen = policy.settings(ALICE, QWEN, 1.75);
            assertEquals(91_750, qwen.threshold());
            assertEquals(131_072, qwen.window());
            assertEquals(1.75, qwen.bytesPerToken(), "the route's coefficient");
        }
    }

    @Test
    void anUnknownOrTooSmallWindowMeansNoCompaction() throws Exception {
        try (UserSettings users = UserSettings.inMemory(Clock.systemUTC())) {
            CompactionPolicy policy = new CompactionPolicy(users, LIMITS);
            assertNull(policy.settings(ALICE, model("claude", "x", 0, 64_000), 1.5));
            assertEquals(List.of(), policy.points(model("claude", "x", 0, 64_000)));
            assertNull(policy.settings(ALICE, model("claude", "x", 64_000, 64_000), 1.5));
        }
    }
}
