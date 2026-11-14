package tantalum.shell.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.local.fs.PrivateFiles;
import tantalum.shell.access.PluginSwitches.Key;
import tantalum.shell.auth.UserStore;
import tantalum.shell.plugin.Kind;
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
            switches.set(ALICE, Kind.PLUGIN, "quota", false);
            assertFalse(switches.enabled(ALICE, Kind.TOOL, "write_file"));
            assertEquals(Set.of(new Key(Kind.TOOL, "write_file"), new Key(Kind.PLUGIN, "quota")), switches.off(ALICE));
            switches.set(ALICE, Kind.TOOL, "write_file", true);
            assertTrue(switches.enabled(ALICE, Kind.TOOL, "write_file"));
            // 种类不同、名字相同是两个开关
            assertTrue(switches.enabled(ALICE, Kind.PLUGIN, "browser"));
        }
    }

    /** 默认关的项（策略插件，A2）：打开要记下来，重开服务后还是开的。 */
    @Test
    void aDefaultOffItemRemembersBeingTurnedOn() throws Exception {
        try (PluginSwitches switches = PluginSwitches.open(data, CLOCK)) {
            assertFalse(switches.enabled(ALICE, Kind.PLUGIN, "readOnly", false));
            switches.set(ALICE, Kind.PLUGIN, "readOnly", true);
        }
        try (PluginSwitches switches = PluginSwitches.open(data, CLOCK)) {
            assertTrue(switches.enabled(ALICE, Kind.PLUGIN, "readOnly", false));
            assertFalse(switches.enabled(BOB, Kind.PLUGIN, "readOnly", false));
            assertEquals(Set.of(), switches.off(ALICE));
        }
    }

    /** 加 enabled 列之前的库只存关掉的项：升级后旧行仍是关，没有的仍按默认。 */
    @Test
    void rowsFromBeforeTheEnabledColumnStillMeanOff() throws Exception {
        try (java.sql.Connection old = java.sql.DriverManager.getConnection("jdbc:sqlite:" + data.resolve(UserStore.FILE));
             java.sql.Statement statement = old.createStatement()) {
            statement.execute("CREATE TABLE plugin_settings (user TEXT NOT NULL, kind TEXT NOT NULL, name TEXT NOT NULL, "
                    + "updated_at TEXT NOT NULL, PRIMARY KEY (user, kind, name))");
            statement.execute("INSERT INTO plugin_settings VALUES ('alice', 'tool', 'write_file', '2026-11-04T00:00:00Z')");
        }
        try (PluginSwitches switches = PluginSwitches.open(data, CLOCK)) {
            assertFalse(switches.enabled(ALICE, Kind.TOOL, "write_file"));
            assertTrue(switches.enabled(ALICE, Kind.TOOL, "edit_file"));
            switches.set(ALICE, Kind.TOOL, "write_file", true);
            assertTrue(switches.enabled(ALICE, Kind.TOOL, "write_file"));
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
        assumeTrue(PrivateFiles.posix(data), "Windows 上权限靠继承的 ACL，见 WindowsFilesTest");
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(data.resolve(UserStore.FILE))));
    }

    /**
     * remove-generic-mcp 需求 1.3：以前存的 MCP 服务器开关（种类 {@code mcp}）和默认值，读的时候跳过，不报错、不影响别的开关。
     * 本机 MCP 的 {@code local-mcp} 记录种类是 {@code local}，留在表里没人问。
     */
    @Test
    void oldMcpRowsAreSkipped() throws Exception {
        try (PluginSwitches switches = PluginSwitches.open(data, CLOCK)) {
            switches.set(ALICE, Kind.TOOL, "write_file", false);
        }
        try (java.sql.Connection raw = java.sql.DriverManager.getConnection("jdbc:sqlite:" + data.resolve(UserStore.FILE));
                java.sql.Statement statement = raw.createStatement()) {
            statement.execute("INSERT INTO plugin_settings (user, kind, name, updated_at, enabled) VALUES ('alice', 'mcp', 'github', 'x', 0)");
            statement.execute("INSERT INTO plugin_settings (user, kind, name, updated_at, enabled) VALUES ('alice', 'local', 'local-mcp', 'x', 0)");
            statement.execute("INSERT INTO plugin_defaults (kind, name, enabled, locked, reason, updated_at) VALUES ('mcp', 'github', 0, 1, '', 'x')");
        }
        try (PluginSwitches switches = PluginSwitches.open(data, CLOCK)) {
            assertEquals(Set.of(new Key(Kind.TOOL, "write_file"), new Key(Kind.LOCAL, "local-mcp")), switches.off(ALICE));
            assertTrue(switches.enabled(ALICE, Kind.TOOL, "read_file"));
        }
    }

    @Test
    void kindsAreReadFromTheirKeys() {
        assertThrows(IllegalArgumentException.class, () -> Kind.of("mcp"));
        assertEquals(Kind.LOCAL, Kind.of("local"));
        assertEquals("plugin", Kind.PLUGIN.key());
        assertThrows(IllegalArgumentException.class, () -> Kind.of("hook"));
    }

    /** 管理员定的默认对没动过的人生效；动过的人按自己的。 */
    @Test
    void anAdminDefaultAppliesToThoseWhoHaveNotChosen() throws Exception {
        try (PluginSwitches switches = PluginSwitches.open(data, CLOCK)) {
            switches.set(BOB, Kind.TOOL, "bash", true);
            switches.setPolicy(Kind.TOOL, "bash", false, false, "");
            assertFalse(switches.enabled(ALICE, Kind.TOOL, "bash"));
            assertTrue(switches.enabled(BOB, Kind.TOOL, "bash"));
            // 默认关的插件，管理员改成默认开
            switches.setPolicy(Kind.PLUGIN, "readOnly", true, false, "");
            assertTrue(switches.enabled(ALICE, Kind.PLUGIN, "readOnly", false));
        }
        try (PluginSwitches switches = PluginSwitches.open(data, CLOCK)) {
            assertFalse(switches.enabled(ALICE, Kind.TOOL, "bash"));
            assertEquals(new PluginSwitches.Policy(false, false, ""), switches.policy(Kind.TOOL, "bash").orElseThrow());
        }
    }

    /** 锁住：所有人都是管理员定的值，用户的选择留在库里，解锁后回来。 */
    @Test
    void aLockOverridesTheUsersChoiceWithoutDeletingIt() throws Exception {
        try (PluginSwitches switches = PluginSwitches.open(data, CLOCK)) {
            switches.set(ALICE, Kind.TOOL, "bash", true);
            switches.setPolicy(Kind.TOOL, "bash", false, true, " 安全要求 ");
            assertFalse(switches.enabled(ALICE, Kind.TOOL, "bash"));
            assertEquals(java.util.Optional.of(true), switches.chosen(ALICE, Kind.TOOL, "bash"));
            assertEquals("安全要求", switches.policy(Kind.TOOL, "bash").orElseThrow().reason());
        }
        try (PluginSwitches switches = PluginSwitches.open(data, CLOCK)) {
            assertFalse(switches.enabled(ALICE, Kind.TOOL, "bash"));
            switches.setPolicy(Kind.TOOL, "bash", false, false, "");
            assertTrue(switches.enabled(ALICE, Kind.TOOL, "bash"));
        }
    }

    /** 只锁、不改默认：锁在这一项自带的默认上。 */
    @Test
    void aLockWithoutADefaultPinsTheBuiltInDefault() throws Exception {
        try (PluginSwitches switches = PluginSwitches.inMemory(CLOCK)) {
            switches.set(ALICE, Kind.PLUGIN, "readOnly", true);
            switches.set(ALICE, Kind.TOOL, "bash", false);
            switches.setPolicy(Kind.PLUGIN, "readOnly", null, true, "");
            switches.setPolicy(Kind.TOOL, "bash", null, true, "");
            assertFalse(switches.enabled(ALICE, Kind.PLUGIN, "readOnly", false));
            assertTrue(switches.enabled(ALICE, Kind.TOOL, "bash", true));
        }
    }

    @Test
    void clearingAPolicyOrAChoiceGoesBackToTheDefault() throws Exception {
        try (PluginSwitches switches = PluginSwitches.inMemory(CLOCK)) {
            switches.setPolicy(Kind.TOOL, "bash", false, true, "x");
            switches.setPolicy(Kind.TOOL, "bash", null, false, "");
            assertTrue(switches.policy(Kind.TOOL, "bash").isEmpty());
            assertTrue(switches.enabled(ALICE, Kind.TOOL, "bash"));

            switches.set(ALICE, Kind.TOOL, "bash", false);
            switches.clear(ALICE, Kind.TOOL, "bash");
            assertTrue(switches.chosen(ALICE, Kind.TOOL, "bash").isEmpty());
            assertTrue(switches.enabled(ALICE, Kind.TOOL, "bash"));
            assertEquals(Set.of(), switches.off(ALICE));
        }
    }
}
