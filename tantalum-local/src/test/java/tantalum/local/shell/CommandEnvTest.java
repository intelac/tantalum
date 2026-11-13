package tantalum.local.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 命令和本机 MCP 子进程的环境变量（connector-jar 需求 4.5）。 */
class CommandEnvTest {

    private static final Map<String, String> HOST = Map.of(
            "PATH", "/usr/bin", "JAVA_HOME", "/jdk", "APPDATA", "C:\\Users\\a\\AppData\\Roaming",
            "ANTHROPIC_API_KEY", "sk", "GITHUB_TOKEN", "gh", "DB_PASSWORD", "pw", "aws_secret_access_key", "s",
            "TANTALUM_TOKEN", "tc_x", "TANTALUM_DATADIR", "/data");

    @Test
    void keepsOrdinaryVariablesAndDropsSecretLookingOnes() {
        Map<String, String> env = CommandEnv.of(HOST, List.of());
        assertEquals(Map.of("PATH", "/usr/bin", "JAVA_HOME", "/jdk", "APPDATA", "C:\\Users\\a\\AppData\\Roaming"), env);
    }

    @Test
    void envNamesPassASecretOnPurposeButNeverTheConnectorToken() {
        Map<String, String> env = CommandEnv.of(HOST, List.of("github_token", "TANTALUM_TOKEN"));
        assertEquals("gh", env.get("GITHUB_TOKEN"), "--env names are case-insensitive, like Windows");
        assertFalse(env.containsKey("TANTALUM_TOKEN"));
        assertFalse(env.containsKey("ANTHROPIC_API_KEY"));
    }

    @Test
    void secretNames() {
        assertTrue(CommandEnv.secret("api_key"));
        assertTrue(CommandEnv.secret("MY_SECRET"));
        assertFalse(CommandEnv.secret("HOME"));
    }
}
