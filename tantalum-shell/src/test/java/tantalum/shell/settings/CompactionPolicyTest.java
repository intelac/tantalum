package tantalum.shell.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.shell.model.ContextWindow;
import tantalum.shell.model.WindowSource;
import tantalum.shell.settings.CompactionPolicy.Limits;
import tantalum.shell.settings.CompactionPolicy.Point;
import tantalum.value.id.Principal;
import tantalum.value.state.CompactionSettings;

/** compaction-threshold T4：压缩点 = min(W × 百分比, W − R)，需求里的两组例子。 */
class CompactionPolicyTest {

    private static final Principal ALICE = new Principal("alice");
    private static final Principal BOB = new Principal("bob");
    private static final Limits CLAUDE = new Limits(64_000, 20_000, 32_768, 4_096, 8_192, 1.5);
    private static final Limits QWEN = new Limits(16_384, 20_000, 32_768, 4_096, 8_192, 2.0);

    private static CompactionPolicy policy(long window, Limits limits, UserSettings users) {
        return new CompactionPolicy(new ContextWindow(WindowSource.NONE, window, limits.reserve(), Duration.ofHours(1)), users, limits);
    }

    @Test
    void claudeAtOneMillion() throws Exception {
        try (UserSettings users = UserSettings.inMemory(Clock.systemUTC())) {
            CompactionPolicy policy = policy(1_000_000, CLAUDE, users);
            assertEquals(List.of(new Point(50, 500_000), new Point(70, 700_000), new Point(85, 850_000), new Point(100, 936_000)),
                    policy.points());
            assertEquals(new CompactionSettings(1_000_000, 64_000, 20_000, 32_768, 4_096, 8_192, 936_000, 1.5), policy.settings(ALICE),
                    "nobody chose: full");
            users.setCompactionPercent(ALICE, 50);
            assertEquals(500_000, policy.settings(ALICE).threshold());
            assertEquals(936_000, policy.settings(BOB).threshold(), "each user has their own");
            assertEquals(1.5, policy.settings(ALICE).bytesPerToken());
        }
    }

    @Test
    void localQwen() throws Exception {
        try (UserSettings users = UserSettings.inMemory(Clock.systemUTC())) {
            assertEquals(List.of(new Point(50, 65_536), new Point(70, 91_750), new Point(85, 111_411), new Point(100, 114_688)),
                    policy(131_072, QWEN, users).points());
        }
    }

    @Test
    void anUnknownOrTooSmallWindowMeansNoCompaction() throws Exception {
        try (UserSettings users = UserSettings.inMemory(Clock.systemUTC())) {
            assertNull(policy(0, CLAUDE, users).settings(ALICE));
            assertEquals(List.of(), policy(0, CLAUDE, users).points());
            assertNull(policy(64_000, CLAUDE, users).settings(ALICE));
        }
    }
}
