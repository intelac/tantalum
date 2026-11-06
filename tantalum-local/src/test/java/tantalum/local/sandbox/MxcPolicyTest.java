package tantalum.local.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import tantalum.local.fs.AllowedDirs;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 交给 MXC 的规则（L13、L14、需求 4.2、4.5）：读写和只读目录按 {@code --allow-write} 分、禁止的目录、网络、环境变量。 */
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "MXC 是 macOS 连接程序的沙箱（/bin/sh、/bin/bash、macOS 的目录）；Windows 连接程序另开 spec")
class MxcPolicyTest {

    @TempDir
    Path temp;

    private Path work;
    private Path other;
    private Path run;
    private Path home;

    @BeforeEach
    void setUp() throws Exception {
        work = Files.createDirectory(temp.toRealPath().resolve("work"));
        other = Files.createDirectory(temp.toRealPath().resolve("other"));
        run = Files.createDirectory(temp.toRealPath().resolve("run"));
        home = Files.createDirectory(temp.toRealPath().resolve("home"));
    }

    private JsonNode config(MxcPolicy policy) {
        return JsonMapper.builder().build().readTree(policy.config("echo hi", work, run, home, Duration.ofSeconds(120)));
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(item -> values.add(item.asString()));
        return values;
    }

    private MxcPolicy policy(boolean write, boolean network, List<String> env, Map<String, String> hostEnv) throws Exception {
        return MxcPolicy.of(new AllowedDirs(List.of(work, other)), write, List.of(Path.of("/opt/tools")), network, env, hostEnv);
    }

    /** 没有 --allow-write：允许的目录只读，能写的只有这次调用的临时目录。 */
    @Test
    void withoutWriteTheAllowedDirectoriesAreReadOnly() throws Exception {
        JsonNode filesystem = config(policy(false, true, List.of(), Map.of())).get("filesystem");
        assertEquals(List.of(run.toString()), strings(filesystem.get("readwritePaths")));
        List<String> readonly = strings(filesystem.get("readonlyPaths"));
        assertTrue(readonly.containsAll(List.of(work.toString(), other.toString(), "/opt/tools", home.toString())), readonly.toString());
        for (String toolchain : MxcPolicy.TOOLCHAINS) {
            assertEquals(Files.isDirectory(Path.of(toolchain)), readonly.contains(toolchain), toolchain);
        }
    }

    /** 有 --allow-write：允许的目录和临时目录可写；家目录、--allow-read 的目录仍然只读。 */
    @Test
    void withWriteOnlyTheAllowedDirectoriesAndTheRunDirectoryAreWritable() throws Exception {
        JsonNode filesystem = config(policy(true, true, List.of(), Map.of())).get("filesystem");
        assertEquals(List.of(work.toString(), other.toString(), run.toString()), strings(filesystem.get("readwritePaths")));
        List<String> readonly = strings(filesystem.get("readonlyPaths"));
        assertFalse(readonly.contains(work.toString()));
        assertTrue(readonly.containsAll(List.of("/opt/tools", home.toString())), readonly.toString());
    }

    /** ~/.ssh、~/Library/Keychains 一律禁止；连本机一律拒绝；出站按 --no-network。 */
    @Test
    void secretsAreDeniedAndLoopbackIsNeverReachable() throws Exception {
        String userHome = System.getProperty("user.home");
        JsonNode open = config(policy(true, true, List.of(), Map.of()));
        assertEquals(List.of(userHome + "/.ssh", userHome + "/Library/Keychains"), strings(open.get("filesystem").get("deniedPaths")));
        assertEquals("allow", open.get("network").get("egress").get("default").asString());
        assertEquals("deny", open.get("network").get("ingress").get("default").asString());
        assertEquals("deny", open.get("network").get("ingress").get("hostLoopback").asString());

        JsonNode closed = config(policy(true, false, List.of(), Map.of()));
        assertEquals("deny", closed.get("network").get("egress").get("default").asString());
        assertEquals("deny", closed.get("network").get("ingress").get("hostLoopback").asString());
    }

    /**
     * 环境变量只有 PATH、HOME（连接程序自己的目录）、TMPDIR（这次的临时目录）、LANG 和 --env 放行的；像密钥的名字和 TANTALUM_TOKEN
     * 就算被放行也不给，HOME 和 TMPDIR 不能被 --env 改掉。
     */
    @Test
    void theEnvironmentHasNoSecrets() throws Exception {
        Map<String, String> host = Map.of("JAVA_HOME", "/opt/jdk", "TANTALUM_TOKEN", "tc_x", "MY_API_KEY", "k", "GH_TOKEN", "t",
                "DB_PASSWORD", "p", "client_secret", "s", "HOME", "/Users/yi", "SHELL", "/bin/zsh");
        MxcPolicy policy = policy(false, true, List.of("JAVA_HOME", "TANTALUM_TOKEN", "MY_API_KEY", "GH_TOKEN", "DB_PASSWORD",
                "client_secret", "HOME", "MISSING"), host);
        List<String> env = strings(config(policy).get("process").get("env"));
        assertEquals(List.of("PATH=" + MxcPolicy.PATH, "HOME=" + home, "TMPDIR=" + run, "LANG=en_US.UTF-8", "JAVA_HOME=/opt/jdk"), env);
        for (String entry : env) {
            String name = entry.substring(0, entry.indexOf('='));
            assertFalse(MxcPolicy.secret(name), entry);
        }
    }

    @Test
    void secretNamesAreRecognisedWhateverTheCase() {
        for (String name : List.of("TANTALUM_TOKEN", "AWS_SECRET_ACCESS_KEY", "api_key", "npm_token", "PGPASSWORD", "Monkey")) {
            assertTrue(MxcPolicy.secret(name), name);
        }
        for (String name : List.of("JAVA_HOME", "MAVEN_OPTS", "PATH", "LANG")) {
            assertFalse(MxcPolicy.secret(name), name);
        }
    }

    /** 命令原样交给 bash：单引号、$、反引号都不会被外面那层展开。 */
    @Test
    void theCommandRunsInBashExactlyAsGiven() throws Exception {
        String command = "echo 'it'\"'\"'s' $HOME `date` \\n";
        String line = JsonMapper.builder().build().readTree(policy(false, true, List.of(), Map.of())
                .config(command, work, run, home, Duration.ofSeconds(5))).get("process").get("commandLine").asString();
        assertEquals("/bin/bash -c " + MxcPolicy.quote(command), line);
        Process bash = new ProcessBuilder("/bin/sh", "-c", line.replace("/bin/bash -c ", "printf %s ")).start();
        assertEquals(command, new String(bash.getInputStream().readAllBytes()));
        assertEquals(work.toString(), config(policy(false, true, List.of(), Map.of())).get("process").get("cwd").asString());
        assertEquals(120_000, config(policy(false, true, List.of(), Map.of())).get("process").get("timeout").asLong());
    }
}
