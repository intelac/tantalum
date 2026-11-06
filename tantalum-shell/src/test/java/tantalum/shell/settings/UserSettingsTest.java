package tantalum.shell.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.access.PluginSwitches;
import tantalum.shell.access.PluginSwitches.Kind;
import tantalum.shell.auth.UserStore;
import tantalum.value.id.Principal;
import tantalum.value.model.ModelChoice;

/** compaction-threshold T5、需求 2.3、2.4：每个用户一份，存在 users.db，四个值之外拒绝。 */
class UserSettingsTest {

    private static final Principal ALICE = new Principal("alice");
    private static final Principal BOB = new Principal("bob");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-04T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path data;

    @Test
    void unsetMeansFull() throws Exception {
        try (UserSettings settings = UserSettings.open(data, CLOCK)) {
            assertEquals(100, settings.compactionPercent(ALICE));
        }
    }

    @Test
    void aChoiceSurvivesARestartAndStaysWithItsUser() throws Exception {
        try (UserSettings settings = UserSettings.open(data, CLOCK)) {
            settings.setCompactionPercent(ALICE, 50);
            assertEquals(50, settings.compactionPercent(ALICE));
            assertEquals(100, settings.compactionPercent(BOB));
            settings.setCompactionPercent(ALICE, 85);
        }
        try (UserSettings reopened = UserSettings.open(data, CLOCK)) {
            assertEquals(85, reopened.compactionPercent(ALICE));
            assertEquals(100, reopened.compactionPercent(BOB));
        }
    }

    @Test
    void onlyTheFourChoicesAreAccepted() throws Exception {
        try (UserSettings settings = UserSettings.inMemory(CLOCK)) {
            for (int wrong : new int[] {0, 49, 60, 101, -50}) {
                assertThrows(IllegalArgumentException.class, () -> settings.setCompactionPercent(ALICE, wrong));
            }
            assertEquals(100, settings.compactionPercent(ALICE), "a refused value changes nothing");
            for (int right : UserSettings.COMPACTION_PERCENTS) {
                settings.setCompactionPercent(ALICE, right);
                assertEquals(right, settings.compactionPercent(ALICE));
            }
        }
    }

    /** 以后版本写的档位或坏掉的值，这个版本按默认读。 */
    @Test
    void anUnknownStoredValueReadsAsFull() throws Exception {
        try (UserSettings settings = UserSettings.open(data, CLOCK)) {
            settings.setCompactionPercent(ALICE, 50);
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + data.resolve(UserStore.FILE));
             var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE user_settings SET value = '60' WHERE user = 'alice'");
            statement.executeUpdate("INSERT INTO user_settings VALUES ('bob', 'compaction_percent', 'x', '2026-11-04')");
        }
        try (UserSettings reopened = UserSettings.open(data, CLOCK)) {
            assertEquals(100, reopened.compactionPercent(ALICE));
            assertEquals(100, reopened.compactionPercent(BOB));
        }
    }

    @Test
    void livesNextToThePluginSwitches() throws Exception {
        try (PluginSwitches switches = PluginSwitches.open(data, CLOCK); UserSettings settings = UserSettings.open(data, CLOCK)) {
            switches.set(ALICE, Kind.TOOL, "write_file", false);
            settings.setCompactionPercent(ALICE, 70);
            assertFalse(switches.enabled(ALICE, Kind.TOOL, "write_file"));
            assertEquals(70, settings.compactionPercent(ALICE));
        }
    }

    /** model-picker M7、需求 4.1、4.4：上次的选择存在同一张表，重启后还在，每个用户一份；没有档位的模型存成没有 effort。 */
    @Test
    void theLastModelChoiceSurvivesARestartPerUser() throws Exception {
        ModelChoice opus = new ModelChoice("claude", "claude-opus-5-5", "high");
        try (UserSettings settings = UserSettings.open(data, CLOCK)) {
            assertEquals(Optional.empty(), settings.modelChoice(ALICE));
            settings.setModelChoice(ALICE, opus);
            settings.setModelChoice(BOB, new ModelChoice("local", "plain", null));
        }
        try (UserSettings reopened = UserSettings.open(data, CLOCK)) {
            assertEquals(Optional.of(opus), reopened.modelChoice(ALICE));
            assertEquals(Optional.of(new ModelChoice("local", "plain", null)), reopened.modelChoice(BOB));
            assertEquals(100, reopened.compactionPercent(ALICE), "the compaction point is a separate row");
        }
    }

    /** 读不懂的当没有（同压缩点），新会话就用管理员的默认。 */
    @Test
    void anUnreadableModelChoiceIsNone() throws Exception {
        try (UserSettings settings = UserSettings.open(data, CLOCK)) {
            settings.compactionPercent(ALICE);
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + data.resolve(UserStore.FILE));
             var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO user_settings VALUES ('alice', 'model_choice', '{oops', '2026-11-04')");
            statement.executeUpdate("INSERT INTO user_settings VALUES ('bob', 'model_choice', '{\"route\":\"claude\"}', '2026-11-04')");
        }
        try (UserSettings reopened = UserSettings.open(data, CLOCK)) {
            assertEquals(Optional.empty(), reopened.modelChoice(ALICE));
            assertEquals(Optional.empty(), reopened.modelChoice(BOB));
        }
    }
}
