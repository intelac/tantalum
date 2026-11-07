package tantalum.shell.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AdminBootstrapTest {

    @TempDir
    Path dir;

    @Test
    void createsTheFirstUserFromTheEnvironment() throws Exception {
        try (UserStore store = UserStore.open(dir)) {
            AdminBootstrap.ensure(store, Map.of(AdminBootstrap.USER, "admin", AdminBootstrap.PASSWORD, "s3cret-long"));
            assertTrue(store.verify("admin", "s3cret-long"));
        }
    }

    @Test
    void noUsersAndNoEnvironmentFailsWithInstructions() throws Exception {
        try (UserStore store = UserStore.open(dir)) {
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> AdminBootstrap.ensure(store, Map.of()));
            assertTrue(failure.getMessage().contains(AdminBootstrap.USER));
            assertTrue(failure.getMessage().contains(AdminBootstrap.PASSWORD));
            assertEquals(0, store.count());
        }
    }

    @Test
    void partialEnvironmentFails() throws Exception {
        try (UserStore store = UserStore.open(dir)) {
            assertThrows(IllegalStateException.class, () -> AdminBootstrap.ensure(store, Map.of(AdminBootstrap.USER, "admin")));
            assertThrows(IllegalStateException.class, () -> AdminBootstrap.ensure(store, Map.of(AdminBootstrap.USER, "admin", AdminBootstrap.PASSWORD, " ")));
            assertThrows(IllegalStateException.class, () -> AdminBootstrap.ensure(store, Map.of(AdminBootstrap.USER, "admin", AdminBootstrap.PASSWORD, "short")),
                    "review-fixes 需求 11.13：首个管理员的密码也至少 8 位");
            assertEquals(0, store.count());
        }
    }

    @Test
    void existingUsersMeanTheEnvironmentIsIgnored() throws Exception {
        try (UserStore store = UserStore.open(dir)) {
            store.add("alice", "pw");
            AdminBootstrap.ensure(store, Map.of(AdminBootstrap.USER, "admin", AdminBootstrap.PASSWORD, "x"));
            AdminBootstrap.ensure(store, Map.of());
            assertEquals(1, store.count());
        }
    }
}
