package tantalum.shell.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.shell.cli.CliText;
import tantalum.value.text.Language;

class UserCommandTest {

    @TempDir
    Path dir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    private int run(String input, String... args) throws Exception {
        return run(Language.EN, input, args);
    }

    private int run(Language language, String input, String... args) throws Exception {
        return UserCommand.run(args, dir, new BufferedReader(new StringReader(input)), new PrintStream(out, true, StandardCharsets.UTF_8),
                CliText.of(language));
    }

    /** console-i18n 需求 5.4：缺密码、用户已存在的提示按 --lang 说。 */
    @Test
    void messagesFollowTheLanguage() throws Exception {
        assertEquals(2, run(Language.JA, "", "add", "alice"));
        assertEquals("パスワードが必要です。標準入力から 1 行で渡してください", out.toString(StandardCharsets.UTF_8).strip());
        out.reset();
        run(Language.EN, "a-password\n", "add", "alice");
        out.reset();
        assertEquals(1, run(Language.ZH, "another-password\n", "add", "alice"));
        assertEquals("用户已经存在：alice", out.toString(StandardCharsets.UTF_8).strip());
        out.reset();
        assertEquals(1, run(Language.EN, "x-password\n", "reset", "nobody"));
        assertEquals("no such user: nobody", out.toString(StandardCharsets.UTF_8).strip());
        out.reset();
        assertEquals(1, run(Language.ZH, "pw-password\n", "add", "nul"));
        assertEquals("nul 是 Windows 保留的设备名，不能做用户名。", out.toString(StandardCharsets.UTF_8).strip());
    }

    @Test
    void addsAUserWithAPasswordReadFromInput() throws Exception {
        assertEquals(0, run("hunter2-long\n", "add", "alice"));
        try (UserStore store = UserStore.open(dir)) {
            assertTrue(store.verify("alice", "hunter2-long"));
        }
        assertFalse(out.toString().contains("hunter2-long"));
    }

    @Test
    void resetChangesThePassword() throws Exception {
        run("old-password\n", "add", "alice");
        assertEquals(0, run("new-password\n", "reset", "alice"));
        try (UserStore store = UserStore.open(dir)) {
            assertTrue(store.verify("alice", "new-password"));
        }
    }

    @Test
    void disableStopsLogin() throws Exception {
        run("a-password\n", "add", "alice");
        assertEquals(0, run("", "disable", "alice"));
        try (UserStore store = UserStore.open(dir)) {
            assertFalse(store.verify("alice", "a-password"));
        }
    }

    @Test
    void errorsAreReportedWithANonZeroExit() throws Exception {
        assertNotEquals(0, run("", "nonsense"));
        assertNotEquals(0, run("", "add"));
        assertNotEquals(0, run("", "add", "alice"));
        assertNotEquals(0, run("x-password\n", "reset", "nobody"));
        run("a-password\n", "add", "alice");
        assertNotEquals(0, run("another-password\n", "add", "alice"));
    }

    /** ops-hardening-2 需求 2.2：--admin、admin/user、enable、list；最后一个管理员不能取消。 */
    @Test
    void adminSubcommandsAndList() throws Exception {
        assertEquals(0, run("root-password\n", "add", "--admin", "root"));
        assertEquals(0, run("alice-password\n", "add", "alice"));
        out.reset();
        assertEquals(1, run("", "user", "root"));
        assertEquals("root is the last administrator and cannot be disabled or demoted", out.toString(StandardCharsets.UTF_8).strip());
        assertEquals(0, run("", "admin", "alice"));
        assertEquals(0, run("", "user", "root"));
        assertEquals(0, run("", "disable", "root"));
        assertEquals(0, run("", "enable", "root"));
        out.reset();
        assertEquals(0, run("", "list"));
        String listed = out.toString(StandardCharsets.UTF_8);
        assertTrue(listed.contains("root\tuser\tenabled\t"), listed);
        assertTrue(listed.contains("alice\tadmin\tenabled\t"), listed);
        assertEquals(2, run("", "list", "extra"));
    }

    /** review-fixes 需求 11.13：新建和重置的密码至少 8 位，按语言提示。 */
    @Test
    void shortPasswordsAreRefused() throws Exception {
        assertEquals(2, run(Language.EN, "short1\n", "add", "alice"));
        assertEquals("the password must be at least 8 characters", out.toString(StandardCharsets.UTF_8).strip());
        out.reset();
        assertEquals(0, run("long-enough\n", "add", "alice"));
        out.reset();
        assertEquals(2, run(Language.ZH, "1234567\n", "reset", "alice"));
        assertEquals("密码至少 8 个字符", out.toString(StandardCharsets.UTF_8).strip());
        try (UserStore store = UserStore.open(dir)) {
            assertTrue(store.verify("alice", "long-enough"));
        }
    }
}
