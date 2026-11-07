package tantalum.connect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import tantalum.connect.Main.Refused;
import tantalum.connect.Main.Setup;
import tantalum.local.wire.Hello;
import tantalum.value.text.Language;

/** local-workspace L2、L4、需求 2.1、7.3：连接程序的参数、令牌从哪来、启动说明。 */
class MainTest {

    /** 只在 Windows 上跳过的原因。 */
    static final String MAC_CONNECTOR = "连接程序目前只在 macOS 上运行：问候里的 git 检查经 Git.java，标准输入接 /dev/null，归 Windows 连接程序 spec";

    /** 已有的断言是中文的：固定用中文跑（console-i18n 任务 12）。 */
    private static final Words ZH = new Words(Language.ZH);

    /** 测试用的假令牌，格式和真的一样。 */
    private static final String TOKEN = "tc_" + "A".repeat(43);
    private static final Map<String, String> ENV = Map.of("TANTALUM_TOKEN", TOKEN);

    @TempDir
    Path temp;

    private Path work;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
    private final List<String> asked = new ArrayList<>();

    @BeforeEach
    void dirs() throws Exception {
        temp = temp.toRealPath();
        work = Files.createDirectory(temp.resolve("work"));
    }

    private String printed() {
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private Main.Prompt answering(String answer) {
        return question -> {
            asked.add(question);
            return answer == null ? null : answer.toCharArray();
        };
    }

    private Setup setup(String... args) throws Refused {
        return Main.setup(Main.Options.parse(args, ZH), ENV, answering(null), ZH);
    }

    /** 需求 2.1：一个 {@code --allow} 都没给时拒绝启动。 */
    @Test
    void withoutAllowItRefusesToStart() {
        assertEquals(1, Main.run(new String[] {"--allow-write"}, ENV, answering(null), out));
        assertTrue(printed().contains("--allow"), printed());
    }

    /** 需求 2.1：目录不存在或不是目录时拒绝启动，说明是哪一个。 */
    @Test
    void aMissingDirectoryIsRefused() throws Exception {
        Path missing = temp.resolve("missing");
        Refused refused = assertThrows(Refused.class, () -> setup("--allow", work.toString(), "--allow", missing.toString()));
        assertTrue(refused.getMessage().contains(missing.toString()), refused.getMessage());
        Path file = Files.writeString(temp.resolve("file.txt"), "x");
        assertThrows(Refused.class, () -> setup("--allow", file.toString()));
        assertThrows(Refused.class, () -> setup("--allow", work.toString(), "--allow-read", missing.toString()));
    }

    /** 需求 7.3：{@code --help} 列出全部参数。 */
    @Test
    void helpListsEveryOption() {
        assertEquals(0, Main.run(new String[] {"--help"}, Map.of(), answering(null), out));
        for (String option : List.of("--server", "--allow", "--allow-write", "--allow-shell", "--allow-read", "--env", "--no-network",
                "--insecure", "--name", "--mxc", "--help", "TANTALUM_TOKEN")) {
            assertTrue(printed().contains(option), option);
        }
        assertTrue(asked.isEmpty(), "help does not ask for a token");
    }

    /** L2：命令行参数会被同一台电脑上的其他程序看到，像令牌的参数一律拒绝，提示用环境变量；输出里也不能回显它。 */
    @Test
    void aTokenOnTheCommandLineIsRefused() {
        for (String[] args : List.of(
                new String[] {"--allow", "/tmp", TOKEN},
                new String[] {"--allow", "/tmp", "--name", TOKEN},
                new String[] {"--allow=" + TOKEN},
                new String[] {"--token", TOKEN},
                new String[] {"--token=" + TOKEN})) {
            bytes.reset();
            assertEquals(1, Main.run(args, Map.of(), answering(null), out), String.join(" ", args));
            assertTrue(printed().contains("TANTALUM_TOKEN"), printed());
            assertFalse(printed().contains(TOKEN.substring(3)), "the token is echoed: " + printed());
            assertTrue(asked.isEmpty());
        }
    }

    /** 只是名字里有 {@code tc_} 的目录（如 {@code etc_backup}）不算令牌。 */
    @Test
    void aDirectoryWhoseNameContainsTcIsFine() throws Exception {
        Path backup = Files.createDirectory(temp.resolve("etc_backup"));
        assertEquals(List.of(new Hello.Root(backup.toString())), setup("--allow", backup.toString()).hello().roots());
    }

    /**
     * B2（2026-11-06 Yi 定）：家目录和根目录不能当允许的目录，说明里给出具体的项目目录和 cd 之后 --allow . 的写法。
     * 按真实路径比较：{@code --allow .}、符号链接都拦；家目录下面的项目目录照常可以。
     */
    @Test
    void theHomeDirectoryAndTheRootAreRefused() throws Exception {
        Path home = Files.createDirectory(temp.resolve("home"));
        Path project = Files.createDirectories(home.resolve("work/shop"));
        Map<String, String> env = Map.of("TANTALUM_TOKEN", TOKEN, "HOME", home.toString());
        Path link = Files.createSymbolicLink(temp.resolve("me"), home);
        for (String dir : List.of(home.toString(), link.toString(), home + "/work/..")) {
            Refused refused = assertThrows(Refused.class, () -> Main.setup(Main.Options.parse(new String[] {"--allow", dir}, ZH), env, answering(null), ZH), dir);
            assertTrue(refused.getMessage().contains("不能是家目录"), refused.getMessage());
            assertTrue(refused.getMessage().contains("--allow ."), refused.getMessage());
            assertTrue(refused.getMessage().contains(home.toRealPath().resolve("work/shop").toString()), refused.getMessage());
        }
        Refused root = assertThrows(Refused.class, () -> Main.setup(Main.Options.parse(new String[] {"--allow", "/"}, ZH), env, answering(null), ZH));
        assertTrue(root.getMessage().contains("不能是根目录"), root.getMessage());
        assertThrows(Refused.class, () -> Main.setup(Main.Options.parse(new String[] {"--allow", project.toString(), "--allow", home.toString()}, ZH),
                env, answering(null), ZH), "not as a second directory either");
        assertEquals(List.of(new Hello.Root(project.toRealPath().toString())),
                Main.setup(Main.Options.parse(new String[] {"--allow", project.toString()}, ZH), env, answering(null), ZH).hello().roots());
        assertEquals(1, Main.run(new String[] {"--allow", home.toString(), "--lang", "zh"}, env, answering(null), out));
        assertTrue(printed().contains("不能是家目录"), printed());
    }

    @Test
    void unknownOptionsAndMissingValuesAreRefused() {
        assertThrows(Refused.class, () -> Main.Options.parse(new String[] {"--allow", work.toString(), "--yes"}, ZH));
        assertThrows(Refused.class, () -> Main.Options.parse(new String[] {"--allow"}, ZH));
        assertThrows(Refused.class, () -> Main.Options.parse(new String[] {"--allow", work.toString(), "extra"}, ZH));
    }

    /** L2：令牌先看环境变量，没有就在终端问，不回显。 */
    @Test
    void theTokenComesFromTheEnvironmentOrAQuietPrompt() throws Exception {
        assertEquals(TOKEN, Main.setup(Main.Options.parse(new String[] {"--allow", work.toString()}, ZH), ENV, answering(null), ZH).token());
        assertTrue(asked.isEmpty());

        Setup prompted = Main.setup(Main.Options.parse(new String[] {"--allow", work.toString()}, ZH), Map.of(), answering("  " + TOKEN + "\n"), ZH);
        assertEquals(TOKEN, prompted.token());
        assertEquals(1, asked.size());

        Refused noConsole = assertThrows(Refused.class,
                () -> Main.setup(Main.Options.parse(new String[] {"--allow", work.toString()}, ZH), Map.of(), answering(null), ZH));
        assertTrue(noConsole.getMessage().contains("TANTALUM_TOKEN"));
    }

    /** 粘错了东西：说不像令牌，但不回显粘进来的内容。 */
    @Test
    void somethingThatIsNotATokenIsRefusedWithoutEchoingIt() {
        Refused refused = assertThrows(Refused.class, () -> Main.setup(Main.Options.parse(new String[] {"--allow", work.toString()}, ZH),
                Map.of("TANTALUM_TOKEN", "sk-secret-value"), answering(null), ZH));
        assertFalse(refused.getMessage().contains("sk-secret-value"));
    }

    /** 设计“toString 不带令牌”：启动设置被打印或写进异常时，令牌也不会跟着出去。 */
    @Test
    void theSetupNeverPrintsTheToken() throws Exception {
        Setup setup = setup("--allow", work.toString());
        assertFalse(setup.toString().contains(TOKEN.substring(3)));
        Main.summary(setup, out, ZH);
        assertFalse(printed().contains(TOKEN.substring(3)));
    }

    /** L14：名字里有 KEY、TOKEN、SECRET、PASSWORD 的变量不能放行给命令。 */
    @Test
    void secretLookingEnvironmentNamesAreRefused() throws Exception {
        for (String name : List.of("AWS_SECRET_ACCESS_KEY", "GITHUB_TOKEN", "DB_PASSWORD", "api_key", "TANTALUM_TOKEN")) {
            assertThrows(Refused.class, () -> setup("--allow", work.toString(), "--env", name), name);
        }
        assertEquals(List.of("JAVA_HOME", "MAVEN_OPTS"), setup("--allow", work.toString(), "--env", "JAVA_HOME", "--env", "MAVEN_OPTS")
                .options().env());
    }

    /**
     * {@code hello}：允许目录的真实路径、电脑上有没有 git（B1：不再按目录报是不是仓库，协议 2）、能否改文件；命令行不可用时如实说原因
     * （L13，需求 4.3）。
     */
    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = MAC_CONNECTOR)
    void theHelloSaysWhatThisComputerAllows() throws Exception {
        Path other = Files.createDirectory(temp.resolve("other"));
        Path link = Files.createSymbolicLink(temp.resolve("link"), work);
        Hello hello = setup("--allow", link.toString(), "--allow", other.toString(), "--allow-write", "--name", "Yi's MacBook").hello();
        assertEquals(List.of(new Hello.Root(work.toString()), new Hello.Root(other.toString())), hello.roots());
        assertTrue(hello.git(), "git is installed on this machine; neither directory is a repository");
        assertTrue(hello.write());
        assertEquals(new Hello.Shell(false, "--allow-shell was not given", "shell/not-allowed", Map.of()), hello.shell());
        assertTrue(hello.network());
        assertEquals("Yi's MacBook", hello.device().name());
        assertEquals(2, hello.protocol());

        Path missing = temp.resolve("no-mxc");
        Hello shell = setup("--allow", work.toString(), "--allow-shell", "--no-network", "--mxc", missing.toString()).hello();
        assertFalse(shell.shell().available());
        assertTrue(shell.shell().reason().contains(missing.toString()) || shell.shell().reason().contains("macOS"), shell.shell().reason());
        assertFalse(shell.network());
        assertFalse(shell.write());
    }

    @Test
    void theServerAddressBecomesAWebSocketAddress() throws Exception {
        assertEquals(URI.create("ws://127.0.0.1:8091/api/local/connect"), setup("--allow", work.toString()).socket());
        assertEquals(URI.create("wss://tantalum.example.com/api/local/connect"),
                setup("--allow", work.toString(), "--server", "https://tantalum.example.com/").socket());
        assertThrows(Refused.class, () -> setup("--allow", work.toString(), "--server", "ftp://example.com"));
    }

    /** review-fixes 需求 11.10：{@code --allow-read} 和 {@code --allow} 一样不能是家目录或根目录。 */
    @Test
    void allowReadFollowsTheSameRulesAsAllow() throws Exception {
        Path home = Files.createDirectory(temp.resolve("home"));
        Map<String, String> env = Map.of("TANTALUM_TOKEN", TOKEN, "HOME", home.toString());
        Refused refused = assertThrows(Refused.class, () -> Main.setup(
                Main.Options.parse(new String[] {"--allow", work.toString(), "--allow-read", home.toString()}, ZH), env, answering(null), ZH));
        assertTrue(refused.getMessage().contains("不能是家目录"), refused.getMessage());
        Refused root = assertThrows(Refused.class, () -> Main.setup(
                Main.Options.parse(new String[] {"--allow", work.toString(), "--allow-read", "/"}, ZH), env, answering(null), ZH));
        assertTrue(root.getMessage().contains("不能是根目录"), root.getMessage());
        Path extra = Files.createDirectory(home.resolve("extra"));
        assertEquals(List.of(extra), Main.setup(Main.Options.parse(new String[] {"--allow", work.toString(), "--allow-read", extra.toString()}, ZH),
                env, answering(null), ZH).options().read());
    }

    /** review-fixes 需求 11.10：不在本机的服务器用明文 http 时令牌会明文经过网络，除非明确给 {@code --insecure}。 */
    @Test
    void aPlainHttpServerOffThisComputerNeedsInsecure() throws Exception {
        Refused refused = assertThrows(Refused.class, () -> setup("--allow", work.toString(), "--server", "http://tantalum.example.com"));
        assertTrue(refused.getMessage().contains("--insecure"), refused.getMessage());
        assertTrue(refused.getMessage().contains("https://"), refused.getMessage());
        assertEquals(URI.create("ws://tantalum.example.com/api/local/connect"),
                setup("--allow", work.toString(), "--server", "http://tantalum.example.com", "--insecure").socket());
        assertEquals(URI.create("ws://localhost:8091/api/local/connect"),
                setup("--allow", work.toString(), "--server", "http://localhost:8091").socket());
        assertEquals(URI.create("ws://[::1]:8091/api/local/connect"),
                setup("--allow", work.toString(), "--server", "http://[::1]:8091").socket());
        assertEquals(URI.create("wss://tantalum.example.com/api/local/connect"),
                setup("--allow", work.toString(), "--server", "https://tantalum.example.com").socket());
    }

    /** 启动说明：连到哪、允许什么、命令行为什么不能用。 */
    @Test
    void theSummarySaysWhatIsAllowed() throws Exception {
        Path other = Files.createDirectory(temp.resolve("other"));
        Main.summary(setup("--allow", work.toString(), "--allow", other.toString(), "--allow-shell", "--mxc",
                temp.resolve("no-mxc").toString()), out, ZH);
        String summary = printed();
        assertTrue(summary.contains("http://127.0.0.1:8091"), summary);
        assertTrue(summary.contains(work.toString()));
        assertTrue(summary.contains(other.toString()));
        assertTrue(summary.contains("命令行：不可用，"), summary);
        assertTrue(summary.contains("--allow-write"), "says how to allow file changes: " + summary);
    }
}
