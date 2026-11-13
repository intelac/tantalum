package tantalum.connect;

import java.io.Console;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import tantalum.connect.Words.Key;
import tantalum.connect.browser.Installed;
import tantalum.connect.browser.BrowserSettings;
import tantalum.connect.browser.NodeFinder;
import tantalum.connect.browser.PlaywrightBrowser;
import tantalum.connect.browser.Profile;
import tantalum.local.fs.AllowedDirs;
import tantalum.connect.mcp.McpConfig;
import tantalum.connect.mcp.McpHost;
import tantalum.local.shell.CommandEnv;
import tantalum.local.shell.LocalShell;
import tantalum.local.shell.Shell;
import tantalum.local.shell.ShellFinder;
import tantalum.local.shell.ShellKind;
import tantalum.local.shell.Unavailable;
import tantalum.local.tool.GitCheck;
import tantalum.local.wire.Hello;
import tantalum.local.wire.McpServerState;
import tantalum.local.wire.Wire;
import tantalum.value.text.Language;

/**
 * 连接程序的入口。允许什么只由这里的启动参数决定；令牌只从环境变量
 * {@code TANTALUM_TOKEN} 或不回显的终端输入读，不从命令行参数读，也不写进任何文件。
 * 提示按 {@code --lang} 或系统的语言说（{@link Words}）。
 */
public final class Main {

    static final String TOKEN_VARIABLE = "TANTALUM_TOKEN";
    static final String TOKEN_PREFIX = "tc_";
    static final String DEFAULT_SERVER = "http://127.0.0.1:8091";
    static final String PATH = "/api/local/connect";
    /** 启动被拒（参数不对、目录不存在、没有令牌）。 */
    static final int REFUSED = 1;
    /** 去掉了的参数：命令不再在沙箱里跑，这些限制不存在了（connector-jar）。给了就说清楚，不悄悄忽略。 */
    static final List<String> REMOVED = List.of("--mxc", "--no-network", "--allow-read");
    /** Playwright MCP 写临时文件的目录，在连接程序自己的目录下；浏览器每次调用后清空。 */
    static final String PLAYWRIGHT_OUTPUT = "playwright-output";
    private static final Pattern ENV_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    /** 参数里夹着一整个令牌（如 {@code --allow=tc_…}）。令牌的随机部分是 43 个 base64url 字符。 */
    private static final Pattern EMBEDDED_TOKEN = Pattern.compile(TOKEN_PREFIX + "[A-Za-z0-9_-]{40,}");

    /** {@code --lang} 的值不对时，还不知道该用哪种语言，一律用英文。 */
    static final String BAD_LANGUAGE = "--lang must be zh, en or ja";

    private Main() {}

    public static void main(String[] args) {
        // MCP SDK 用 SLF4J，连接程序不带日志实现：不加这一句，允许浏览器时每次启动打三行“No SLF4J providers”警告（N3，2026-11-14）
        System.setProperty("slf4j.internal.verbosity", "ERROR");
        System.exit(run(args, System.getenv(), Main::readQuietly, System.out));
    }

    /** 从终端读令牌，不回显；没有终端时返回 null。 */
    private static char[] readQuietly(String question) {
        Console console = System.console();
        return console == null ? null : console.readPassword("%s", question);
    }

    /** 测试从这里进：环境变量、终端输入、输出都换得掉。返回退出码。 */
    static int run(String[] args, Map<String, String> env, Prompt prompt, PrintStream out) {
        Language language;
        try {
            language = language(args, env);
        } catch (IllegalArgumentException bad) {
            out.println(BAD_LANGUAGE);
            return REFUSED;
        }
        Words words = new Words(language);
        Setup setup;
        try {
            Options options = Options.parse(args, words);
            if (options.help()) {
                out.print(words.say(Key.HELP));
                return 0;
            }
            setup = setup(options, env, prompt, words);
        } catch (Refused refused) {
            out.println(refused.getMessage());
            return REFUSED;
        }
        summary(setup, out, words);
        Journal journal = new Journal(out, Clock.systemDefaultZone());
        // 本机 MCP 服务器在后台启动，好了以后经连接补报工具（connector-jar 需求 6）；连接还没建起来时先记下，连上时 hello 里带着。
        // 浏览器是其中内置的 playwright 一项（需求 7），它的工具不当作 mcp__ 工具报给服务器，由 browser_* 用
        AtomicReference<Link> link = new AtomicReference<>();
        boolean browserOn = setup.browser() != null;
        McpHost mcp = new McpHost(setup.mcp(), setup.commandEnv(), setup.dirs().main(), state -> {
            Link current = link.get();
            if (current != null && !(browserOn && state.name().equals(PlaywrightBrowser.SERVER))) {
                current.report(state);
            }
        });
        PlaywrightBrowser browser = !browserOn ? null
                : new PlaywrightBrowser((tool, arguments) -> mcp.call(McpHost.CALL_PREFIX + PlaywrightBrowser.SERVER + ":" + tool, arguments),
                Profile.home().resolve(PLAYWRIGHT_OUTPUT));
        Calls calls = new Calls(setup.dirs(), setup.hello().write(), setup.hello().shell().available(), setup.shell(), journal, browser);
        calls.mcp(mcp, browserOn ? PlaywrightBrowser.SERVER : null);
        // Ctrl+C：打断在跑的调用，关本机 MCP 服务器（连同浏览器），再结束这个程序启动的全部进程（命令、git）
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            calls.stopAll();
            mcp.close();
            ProcessHandle.current().descendants().forEach(ProcessHandle::destroyForcibly);
            setup.shell().close();
        }, "stop-calls"));
        try {
            Link running = new Link(setup, calls, out, words, () -> visible(mcp.states(), browserOn));
            link.set(running);
            mcp.start();
            return running.run();
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            return 0;
        }
    }

    /** 在终端问令牌。返回 null 表示没有终端。 */
    @FunctionalInterface
    interface Prompt {
        char[] secret(String question);
    }

    /**
     * 终端用哪种语言：先看 {@code --lang}（它决定后面所有报错用什么语言，所以先单独解析），没给就看 {@code LC_ALL}、
     * {@code LC_MESSAGES}、{@code LANG}，再看 Java 的系统语言，都不是三种之一时用英文。值不对抛 {@link IllegalArgumentException}。
     */
    static Language language(String[] args, Map<String, String> env) {
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--lang")) {
                return Language.parse(i + 1 < args.length ? args[i + 1] : null).orElseThrow(IllegalArgumentException::new);
            }
        }
        return Language.fromEnvironment(env::get, Locale.getDefault());
    }

    /** 启动参数不对。消息直接给用户看，不含令牌。 */
    static final class Refused extends Exception {

        Refused(String message) {
            super(message);
        }
    }

    /** 解析后的启动参数。 */
    record Options(String server, List<Path> allow, boolean write, boolean shell, List<String> env,
                   String name, boolean help, boolean insecure, BrowserOptions browser, Path mcpConfig) {

        /** 没有浏览器参数时。 */
        Options(String server, List<Path> allow, boolean write, boolean shell, List<String> env, String name, boolean help,
                boolean insecure) {
            this(server, allow, write, shell, env, name, help, insecure, BrowserOptions.NONE, null);
        }

        Options(String server, List<Path> allow, boolean write, boolean shell, List<String> env, String name, boolean help,
                boolean insecure, BrowserOptions browser) {
            this(server, allow, write, shell, env, name, help, insecure, browser, null);
        }

        static Options parse(String[] args, Words words) throws Refused {
            for (String arg : args) {
                if (looksLikeToken(arg)) {
                    throw new Refused(words.say(Key.REFUSED_TOKEN_ON_COMMAND_LINE, Map.of("variable", TOKEN_VARIABLE)));
                }
            }
            String server = DEFAULT_SERVER;
            List<Path> allow = new ArrayList<>();
            List<String> env = new ArrayList<>();
            boolean write = false;
            boolean shell = false;
            boolean insecure = false;
            String name = null;
            Path mcpConfig = null;
            boolean browser = false;
            boolean headless = false;
            Installed.Choice choice = null;
            Path browserPath = null;
            Path profile = null;
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--help", "-h" -> {
                        return new Options(server, List.of(), false, false, List.of(), null, true, false);
                    }
                    case "--server" -> server = value(args, ++i, arg, words);
                    case "--allow" -> allow.add(Path.of(value(args, ++i, arg, words)));
                    case "--env" -> env.add(value(args, ++i, arg, words));
                    case "--name" -> name = value(args, ++i, arg, words);
                    case "--mcp-config" -> mcpConfig = Path.of(value(args, ++i, arg, words));
                    // 已经在 language() 里查过
                    case "--lang" -> i++;
                    case "--allow-write" -> write = true;
                    case "--allow-shell" -> shell = true;
                    case "--insecure" -> insecure = true;
                    case "--allow-browser" -> browser = true;
                    case "--browser-headless" -> headless = true;
                    case "--browser" -> {
                        String value = value(args, ++i, arg, words);
                        choice = Installed.Choice.parse(value)
                                .orElseThrow(() -> new Refused(words.say(Key.REFUSED_BROWSER_CHOICE, Map.of("value", value))));
                    }
                    case "--browser-path" -> browserPath = Path.of(value(args, ++i, arg, words));
                    case "--browser-profile" -> profile = Path.of(value(args, ++i, arg, words));
                    default -> throw new Refused(REMOVED.contains(arg) ? words.say(Key.REFUSED_REMOVED_OPTION, Map.of("option", arg))
                            : words.say(Key.REFUSED_UNKNOWN_OPTION, Map.of("option", arg)));
                }
            }
            if (allow.isEmpty()) {
                throw new Refused(words.say(Key.REFUSED_NO_ALLOW));
            }
            if (!browser) {
                // 浏览器的参数单独给是写漏了 --allow-browser：说清楚，不悄悄忽略
                String stray = choice != null ? "--browser" : browserPath != null ? "--browser-path" : headless ? "--browser-headless"
                        : profile != null ? "--browser-profile" : null;
                if (stray != null) {
                    throw new Refused(words.say(Key.REFUSED_BROWSER_NEEDS_ALLOW, Map.of("option", stray)));
                }
            }
            return new Options(server, List.copyOf(allow), write, shell, List.copyOf(env), name, false,
                    insecure, new BrowserOptions(browser, choice == null ? Installed.Choice.ANY : choice, browserPath, headless, profile),
                    mcpConfig);
        }

        /** 以 {@code tc_} 开头、在哪里带着一整个令牌、或者是 {@code --token}。报错时不回显这个参数。 */
        static boolean looksLikeToken(String arg) {
            return arg.startsWith(TOKEN_PREFIX) || arg.startsWith("--token") || EMBEDDED_TOKEN.matcher(arg).find();
        }

        private static String value(String[] args, int at, String option, Words words) throws Refused {
            if (at >= args.length || args[at].startsWith("--")) {
                throw new Refused(words.say(Key.REFUSED_MISSING_VALUE, Map.of("option", option)));
            }
            return args[at];
        }
    }

    /**
     * 浏览器的启动参数（local-browser 需求 1–3）：{@code --allow-browser}、{@code --browser chrome|edge}、
     * {@code --browser-path 文件}、{@code --browser-headless}、{@code --browser-profile 目录}。后四个要有第一个才收。
     */
    record BrowserOptions(boolean allowed, Installed.Choice choice, Path path, boolean headless, Path profile) {

        static final BrowserOptions NONE = new BrowserOptions(false, Installed.Choice.ANY, null, false, null);
    }

    /**
     * 检查过的启动设置：WebSocket 地址、允许的目录、要发给服务器的 {@code hello}、令牌、跑命令的 shell（没有 {@code --allow-shell}
     * 或找不到时是不能用的那个）。{@link #toString()} 不带令牌，打印或写进异常都不会泄露。
     */
    record Setup(URI socket, Options options, AllowedDirs dirs, Hello hello, String token, Shell shell,
                 BrowserSettings browser, McpConfig mcp, Path mcpFile, Map<String, String> commandEnv) {

        /** 没有本机 MCP 时。 */
        Setup(URI socket, Options options, AllowedDirs dirs, Hello hello, String token, Shell shell, BrowserSettings browser) {
            this(socket, options, dirs, hello, token, shell, browser, McpConfig.empty(), null, Map.of());
        }

        /** 没有浏览器时。 */
        Setup(URI socket, Options options, AllowedDirs dirs, Hello hello, String token, Shell shell) {
            this(socket, options, dirs, hello, token, shell, null);
        }

        @Override
        public String toString() {
            return "Setup[socket=" + socket + ", dirs=" + dirs.all() + ", write=" + hello.write() + "]";
        }
    }

    /** 检查目录、取令牌、找 shell、算出 {@code hello}。 */
    static Setup setup(Options options, Map<String, String> env, Prompt prompt, Words words) throws Refused {
        URI socket = socket(options.server(), words);
        if (!options.insecure() && socket.getScheme().equals("ws") && !loopback(socket.getHost())) {
            // 令牌明文经过网络：只有本机的服务器才允许不加密，别的要 https，或者明确给 --insecure
            throw new Refused(words.say(Key.REFUSED_SERVER_INSECURE, Map.of("server", options.server())));
        }
        AllowedDirs dirs;
        try {
            for (Path dir : options.allow()) {
                if (!Files.isDirectory(dir)) {
                    throw new Refused(words.say(Key.REFUSED_ALLOW_NOT_DIR, Map.of("dir", dir)));
                }
                refuseTooBroad(dir, env, words);
            }
            dirs = new AllowedDirs(options.allow());
        } catch (IOException | IllegalArgumentException failure) {
            throw new Refused(words.say(Key.REFUSED_ALLOW_UNUSABLE, Map.of("detail", String.valueOf(failure.getMessage()))));
        }
        for (String name : options.env()) {
            if (name.equalsIgnoreCase(CommandEnv.TOKEN)) {
                throw new Refused(words.say(Key.REFUSED_ENV_SECRET, Map.of("name", name)));
            }
            if (!ENV_NAME.matcher(name).matches()) {
                throw new Refused(words.say(Key.REFUSED_ENV_NAME, Map.of("name", name)));
            }
        }
        Path npx = options.browser().allowed() ? NodeFinder.npx().orElse(null) : null;
        BrowserSettings found = browser(options.browser(), npx, words);
        String token = token(env, prompt, words);
        Map<String, String> commandEnv = CommandEnv.of(env, options.env());
        Shell shell = shell(options, commandEnv);
        Hello.Browser state = !options.browser().allowed() ? Hello.Browser.unavailable(Hello.Browser.NOT_ALLOWED)
                : found == null ? Hello.Browser.unavailable(Hello.Browser.NOT_FOUND)
                : npx == null ? Hello.Browser.unavailable(Hello.Browser.NODE_MISSING)
                : Hello.Browser.available(found.name(), found.headless());
        BrowserSettings browser = state.available() ? found : null;
        Path mcpFile = options.mcpConfig() != null ? options.mcpConfig()
                : McpConfig.defaultPath(env, System.getProperty("os.name", ""), Path.of(System.getProperty("user.home")));
        if (options.mcpConfig() != null && !Files.isRegularFile(mcpFile)) {
            throw new Refused(words.say(Key.REFUSED_MCP_CONFIG, Map.of("file", mcpFile)));
        }
        removeOldDrivers(Profile.home());
        McpConfig mcp = McpConfig.read(mcpFile);
        if (browser != null) {
            // 内置的 playwright 一项；mcp.json 里有同名的就用文件里的
            mcp = mcp.withDefault(browser.entry(Profile.home().resolve(PLAYWRIGHT_OUTPUT)));
        }
        Hello hello = hello(options, dirs, shell, state, jarSha256());
        // hello 里先带“启动中”，好了再补报（McpHost 的状态和这里一致）
        hello = hello.withMcp(visible(new McpHost(mcp, Map.of(), dirs.main(), ignored -> { }).states(), browser != null))
                .withMcpConfig(mcpFile.toAbsolutePath().toString());
        return new Setup(socket, options, dirs, hello, token, shell, browser, mcp, mcpFile, commandEnv);
    }

    /**
     * 有 {@code --allow-browser} 时找浏览器、建资料目录；只看文件，不启动浏览器。没允许、或者找不到 Chrome 和 Edge 时为 null：
     * 连接程序照常启动，只是没有浏览器，原因写进启动说明和插件页。{@code --browser-path} 指的文件不能用、资料目录建不了是写错了，拒绝启动。
     */
    private static BrowserSettings browser(BrowserOptions options, Path npx, Words words) throws Refused {
        if (!options.allowed()) {
            return null;
        }
        Installed.Found found;
        if (options.path() != null) {
            if (!Installed.usable(options.path())) {
                throw new Refused(words.say(Key.REFUSED_BROWSER_PATH, Map.of("path", options.path())));
            }
            found = new Installed.Found(String.valueOf(options.path().getFileName()), options.path().toAbsolutePath());
        } else {
            found = Installed.find(options.choice()).orElse(null);
        }
        if (found == null) {
            return null;
        }
        Path profile = options.profile() == null ? Profile.standard() : options.profile();
        try {
            profile = Profile.prepare(profile.toAbsolutePath());
        } catch (IOException | RuntimeException failure) {
            throw new Refused(words.say(Key.REFUSED_BROWSER_PROFILE, Map.of("dir", profile, "detail", String.valueOf(failure.getMessage()))));
        }
        return new BrowserSettings(found.path(), found.name(), options.headless(), profile, npx);
    }

    /** 报给服务器的本机 MCP 服务器：浏览器开着时，内置的 playwright 一项不算（它的工具是 browser_*）。 */
    /**
     * 0.1 以前用 Playwright for Java 自己驱动浏览器，下载的驱动放在 {@code browser-driver-<版本>}（约 130 MB）；现在走 Playwright MCP，
     * 用不上了，启动时删掉（N2，2026-11-14 实测看到）。只删这个前缀的目录，删不掉不影响启动。
     */
    static void removeOldDrivers(Path home) {
        try (java.util.stream.Stream<Path> entries = Files.list(home)) {
            for (Path dir : entries.filter(path -> path.getFileName().toString().startsWith("browser-driver-") && Files.isDirectory(path,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS)).toList()) {
                try (java.util.stream.Stream<Path> tree = Files.walk(dir)) {
                    for (Path path : tree.sorted(java.util.Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(path);
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // 目录不在、没权限：留着，不影响连接
        }
    }

    static List<McpServerState> visible(List<McpServerState> states, boolean browserOn) {
        return browserOn ? states.stream().filter(state -> !state.name().equals(PlaywrightBrowser.SERVER)).toList() : states;
    }

    /**
     * 不允许把家目录或根目录 {@code /} 交出去（2026-11-06 Yi 定）：里面有密钥、浏览器资料、别的项目，agent 全都能读。
     * 按真实路径比较，{@code --allow .}、{@code --allow ~}、符号链接都一样拦。家目录取 {@code HOME}，没有时取 {@code user.home}。
     */
    static void refuseTooBroad(Path dir, Map<String, String> env, Words words) throws Refused, IOException {
        Path real = dir.toRealPath();
        if (real.getParent() == null) {
            throw new Refused(words.say(Key.REFUSED_ROOT));
        }
        String home = env.getOrDefault("HOME", System.getProperty("user.home"));
        if (home != null && !home.isBlank() && Files.isDirectory(Path.of(home)) && real.equals(Path.of(home).toRealPath())) {
            throw new Refused(words.say(Key.REFUSED_HOME, Map.of("dir", real, "example", real.resolve("work/shop"))));
        }
    }

    /**
     * 有 {@code --allow-shell} 时找这台电脑的 shell（macOS、Linux 上 bash，Windows 上 PowerShell）。找不到时命令行不出现，原因写进
     * 启动说明和插件页，文件工具照常能用。命令的环境变量是这个程序自己的，去掉像密钥的（{@link CommandEnv}）。
     */
    private static Shell shell(Options options, Map<String, String> commandEnv) {
        if (!options.shell()) {
            return Shell.unavailable(Unavailable.of(Unavailable.Code.SHELL_NOT_ALLOWED));
        }
        return LocalShell.open(ShellFinder.system(), ShellKind.current(), commandEnv);
    }

    /** 连接程序自己所在的 jar 的 SHA-256（十六进制小写）；从源码运行（不是 jar）或读不了时为 null。 */
    static String jarSha256() {
        try {
            java.security.CodeSource source = Main.class.getProtectionDomain().getCodeSource();
            if (source == null) {
                return null;
            }
            Path file = Path.of(source.getLocation().toURI());
            if (!Files.isRegularFile(file) || !file.getFileName().toString().endsWith(".jar")) {
                return null;
            }
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            try (java.io.InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                    digest.update(buffer, 0, read);
                }
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (IOException | java.net.URISyntaxException | java.security.NoSuchAlgorithmException | RuntimeException failure) {
            return null;
        }
    }

    private static URI socket(String server, Words words) throws Refused {
        try {
            URI uri = new URI(server.strip());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String socketScheme = switch (scheme) {
                case "http", "ws" -> "ws";
                case "https", "wss" -> "wss";
                default -> throw new Refused(words.say(Key.REFUSED_SERVER_SCHEME, Map.of("server", server)));
            };
            if (uri.getHost() == null) {
                throw new Refused(words.say(Key.REFUSED_SERVER_ADDRESS, Map.of("server", server)));
            }
            String base = uri.getPath() == null ? "" : uri.getPath().replaceAll("/+$", "");
            return new URI(socketScheme, null, uri.getHost(), uri.getPort(), base + PATH, null, null);
        } catch (URISyntaxException bad) {
            throw new Refused(words.say(Key.REFUSED_SERVER_ADDRESS, Map.of("server", server)));
        }
    }

    /** 本机地址：回环名字或回环 IP。{@code localhost} 不查 DNS，按名字认。 */
    static boolean loopback(String host) {
        if (host == null) {
            return false;
        }
        String bare = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        if (bare.equalsIgnoreCase("localhost") || bare.equals("::1") || bare.startsWith("127.")) {
            return true;
        }
        return bare.equalsIgnoreCase("0:0:0:0:0:0:0:1");
    }

    /** 令牌：环境变量优先，没有就问。只检查长相，不打印内容。 */
    private static String token(Map<String, String> env, Prompt prompt, Words words) throws Refused {
        String token = env.get(TOKEN_VARIABLE);
        if (token == null || token.isBlank()) {
            char[] typed = prompt.secret(words.say(Key.TOKEN_PROMPT));
            if (typed == null) {
                throw new Refused(words.say(Key.REFUSED_NO_TOKEN, Map.of("variable", TOKEN_VARIABLE)));
            }
            token = new String(typed);
            Arrays.fill(typed, '\0');
        }
        token = token.strip();
        if (!token.startsWith(TOKEN_PREFIX) || token.length() > 200 || !token.matches("[A-Za-z0-9_-]+")) {
            throw new Refused(words.say(Key.REFUSED_NOT_A_TOKEN, Map.of("prefix", TOKEN_PREFIX)));
        }
        return token;
    }

    /** Git 只看这台电脑装没装：哪个目录是仓库由每次调用按路径判断。 */
    private static Hello hello(Options options, AllowedDirs dirs, Shell shell, Hello.Browser browser, String jarSha256) {
        List<Hello.Root> roots = new ArrayList<>();
        for (Path dir : dirs.all()) {
            roots.add(new Hello.Root(dir.toString()));
        }
        boolean git;
        try {
            git = GitCheck.installed(dirs.main());
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            git = false;
        }
        return new Hello(Wire.PROTOCOL, version(), device(options.name()), roots, options.write(),
                Hello.Shell.of(shell), true, git, browser, jarSha256, List.of());
    }

    static String version() {
        String version = Main.class.getPackage().getImplementationVersion();
        return version == null ? "dev" : version;
    }

    private static Hello.Device device(String name) {
        String osName = System.getProperty("os.name", "");
        String os = osName.toLowerCase(Locale.ROOT).contains("mac") ? "macos" : osName.toLowerCase(Locale.ROOT).replace(' ', '-');
        String arch = System.getProperty("os.arch", "");
        if (arch.equals("aarch64")) {
            arch = "arm64";
        }
        return new Hello.Device(name == null ? hostName() : name, os, System.getProperty("os.version", ""), arch);
    }

    private static String hostName() {
        try {
            String host = InetAddress.getLocalHost().getHostName();
            return host.endsWith(".local") ? host.substring(0, host.length() - ".local".length()) : host;
        } catch (IOException unknown) {
            return "this computer";
        }
    }

    /** 启动说明：连到哪、允许什么、命令行能不能用以及原因、浏览器的情况。 */
    static void summary(Setup setup, PrintStream out, Words words) {
        Hello hello = setup.hello();
        out.println("Tantalum Connect " + hello.connector());
        out.println(words.say(Key.SUMMARY_SERVER, Map.of("server", setup.options().server().strip())));
        out.println(words.say(Key.SUMMARY_DEVICE, Map.of("name", hello.device().name(), "os", hello.device().os(),
                "version", hello.device().osVersion(), "arch", hello.device().arch())));
        for (int i = 0; i < hello.roots().size(); i++) {
            out.println(words.say(i == 0 ? Key.SUMMARY_MAIN_DIR : Key.SUMMARY_DIR, Map.of("dir", hello.roots().get(i).path())));
        }
        out.println(words.say(hello.git() ? Key.SUMMARY_GIT_YES : Key.SUMMARY_GIT_NO));
        out.println(words.say(hello.write() ? Key.SUMMARY_WRITE_YES : Key.SUMMARY_WRITE_NO));
        Shell.Status shell = setup.shell().check();
        out.println(shell.available() ? words.say(Key.SUMMARY_SHELL_YES, Map.of("program", String.valueOf(setup.shell().program())))
                : words.say(Key.SUMMARY_SHELL_NO, Map.of("reason", words.shell(shell.why()))));
        if (setup.options().shell()) {
            if (!setup.options().env().isEmpty()) {
                out.println(words.say(Key.SUMMARY_ENV, Map.of("names", String.join(", ", setup.options().env()))));
            }
        }
        Hello.Browser browser = hello.browser();
        if (browser == null || Hello.Browser.NOT_ALLOWED.equals(browser.code())) {
            out.println(words.say(Key.SUMMARY_BROWSER_NO));
        } else if (Hello.Browser.NODE_MISSING.equals(browser.code())) {
            out.println(words.say(Key.SUMMARY_BROWSER_NODE));
        } else if (!browser.available()) {
            out.println(words.say(Key.SUMMARY_BROWSER_MISSING));
        } else {
            out.println(words.say(browser.headless() ? Key.SUMMARY_BROWSER_HEADLESS : Key.SUMMARY_BROWSER_WINDOW,
                    Map.of("name", browser.name())));
            out.println(words.say(Key.SUMMARY_BROWSER_PROFILE, Map.of("dir", setup.browser().profile())));
        }
        // 内置的浏览器那一项上面“浏览器：”已经说了，这里不再列成本机 MCP（N4，2026-11-14 实测看到）
        List<McpConfig.Entry> listed = setup.mcp().servers().stream()
                .filter(entry -> setup.browser() == null || !entry.name().equals(PlaywrightBrowser.SERVER)).toList();
        if (!listed.isEmpty() || !setup.mcp().problems().isEmpty()) {
            out.println(words.say(Key.SUMMARY_MCP, Map.of("names", listed.isEmpty() ? "-"
                    : String.join(", ", listed.stream().map(entry -> entry.disabled() ? entry.name() + " (off)" : entry.name()).toList()),
                    "file", String.valueOf(setup.mcpFile()))));
            for (String problem : setup.mcp().problems()) {
                out.println(words.say(Key.SUMMARY_MCP_PROBLEM, Map.of("problem", problem)));
            }
        }
        out.println(words.say(Key.SUMMARY_FOOTER));
        out.flush();
    }
}
