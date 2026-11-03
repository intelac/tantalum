package tantalum.shell.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.access.PluginSwitches.Key;
import tantalum.shell.access.PluginSwitches.Kind;
import tantalum.shell.auth.UserStore;
import tantalum.value.id.Principal;

class PluginSwitchesTest {

    private static final Principal ALICE = new Principal("alice");
    private static final Principal BOB = new Principal("bob");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-04T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path data;

    @Test
    void everythingIsOnByDefault() throws Exception {
        try (PluginSwitches switches = PluginSwitches.open(data, CLOCK)) {
            assertTrue(switches.enabled(ALICE, Kind.TOOL, "write_file"));
            assertEquals(Set.of(), switches.off(ALICE));
        }
    }

    @Test
    void turningOffAndOnAgain() throws Exception {
        try (PluginSwitches switches = PluginSwitches.open(data, CLOCK)) {
            switches.set(ALICE, Kind.TOOL, "write_file", false);
            switches.set(ALICE, Kind.MCP, "browser", false);
            assertFalse(switches.enabled(ALICE, Kind.TOOL, "write_file"));
            assertEquals(Set.of(new Key(Kind.TOOL, "write_file"), new Key(Kind.MCP, "browser")), switches.off(ALICE));
            switches.set(ALICE, Kind.TOOL, "write_file", true);
            assertTrue(switches.enabled(ALICE, Kind.TOOL, "write_file"));
            // 种类不同、名字相同是两个开关
            assertTrue(switches.enabled(ALICE, Kind.PLUGIN, "browser"));
        }
    }

    /** 需求 7 第 6 条：每个用户只影响自己。 */
    @Test
    void usersDoNotShareSwitches() throws Exception {
        try (PluginSwitches switches = PluginSwitches.open(data, CLOCK)) {
            switches.set(ALICE, Kind.PLUGIN, "dailyQuota", false);
            assertFalse(switches.enabled(ALICE, Kind.PLUGIN, "dailyQuota"));
            assertTrue(switches.enabled(BOB, Kind.PLUGIN, "dailyQuota"));
        }
    }

    @Test
    void switchesSurviveARestartAndLiveBesideTheUsers() throws Exception {
        try (UserStore users = UserStore.open(data)) {
            users.add("alice", "a-long-password");
        }
        try (PluginSwitches switches = PluginSwitches.open(data, CLOCK)) {
            switches.set(ALICE, Kind.TOOL, "edit_file", false);
        }
        try (PluginSwitches switches = PluginSwitches.open(data, CLOCK); UserStore users = UserStore.open(data)) {
            assertFalse(switches.enabled(ALICE, Kind.TOOL, "edit_file"));
            assertTrue(users.verify("alice", "a-long-password"));
        }
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(data.resolve(UserStore.FILE))));
    }

    @Test
    void kindsAreReadFromTheirKeys() {
        assertEquals(Kind.MCP, Kind.of("mcp"));
        assertEquals("plugin", Kind.PLUGIN.key());
        assertThrows(IllegalArgumentException.class, () -> Kind.of("hook"));
    }
}
