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
        assertEquals(1, run(Language.ZH, "another\n", "add", "alice"));
        assertEquals("用户已经存在：alice", out.toString(StandardCharsets.UTF_8).strip());
        out.reset();
        assertEquals(1, run(Language.EN, "x\n", "reset", "nobody"));
        assertEquals("no such user: nobody", out.toString(StandardCharsets.UTF_8).strip());
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
        assertNotEquals(0, run("x\n", "reset", "nobody"));
        run("a-password\n", "add", "alice");
        assertNotEquals(0, run("another\n", "add", "alice"));
    }
}
