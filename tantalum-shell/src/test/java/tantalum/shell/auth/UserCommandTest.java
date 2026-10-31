package tantalum.shell.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UserCommandTest {

    @TempDir
    Path dir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    private int run(String input, String... args) throws Exception {
        return UserCommand.run(args, dir, new BufferedReader(new StringReader(input)), new PrintStream(out, true));
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
