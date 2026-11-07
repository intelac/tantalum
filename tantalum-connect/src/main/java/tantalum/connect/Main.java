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
import java.util.regex.Pattern;
import tantalum.connect.Words.Key;
import tantalum.local.fs.AllowedDirs;
import tantalum.local.sandbox.Mxc;
import tantalum.local.sandbox.MxcPolicy;
import tantalum.local.sandbox.Sandbox;
import tantalum.local.sandbox.Unavailable;
import tantalum.local.tool.GitCheck;
import tantalum.local.wire.Hello;
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
    /** 安装包的启动器经这个系统属性告诉程序包里的 {@code mxc-exec-mac} 在哪。 */
    static final String BUNDLED_MXC = "tantalum.mxc";
    private static final Pattern ENV_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    /** 参数里夹着一整个令牌（如 {@code --allow=tc_…}）。令牌的随机部分是 43 个 base64url 字符。 */
    private static final Pattern EMBEDDED_TOKEN = Pattern.compile(TOKEN_PREFIX + "[A-Za-z0-9_-]{40,}");

    /** {@code --lang} 的值不对时，还不知道该用哪种语言，一律用英文。 */
    static final String BAD_LANGUAGE = "--lang must be zh, en or ja";

    private Main() {}

    public static void main(String[] args) {
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
        Calls calls = new Calls(setup.dirs(), setup.hello().write(), setup.hello().shell().available(), setup.sandbox(), journal);
        // Ctrl+C：打断在跑的调用，再结束这个程序启动的全部进程（MXC 和命令、git），最后删掉沙箱的临时目录
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            calls.stopAll();
            ProcessHandle.current().descendants().forEach(ProcessHandle::destroyForcibly);
            setup.sandbox().close();
        }, "stop-calls"));
        try {
            return new Link(setup, calls, out, words).run();
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
    record Options(String server, List<Path> allow, boolean write, boolean shell, List<Path> read, List<String> env,
                   boolean network, String name, Path mxc, boolean help, boolean insecure) {

        static Options parse(String[] args, Words words) throws Refused {
            for (String arg : args) {
                if (looksLikeToken(arg)) {
                    throw new Refused(words.say(Key.REFUSED_TOKEN_ON_COMMAND_LINE, Map.of("variable", TOKEN_VARIABLE)));
                }
            }
            String server = DEFAULT_SERVER;
            List<Path> allow = new ArrayList<>();
            List<Path> read = new ArrayList<>();
            List<String> env = new ArrayList<>();
            boolean write = false;
            boolean shell = false;
            boolean network = true;
            boolean insecure = false;
            String name = null;
            Path mxc = null;
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--help", "-h" -> {
                        return new Options(server, List.of(), false, false, List.of(), List.of(), true, null, null, true, false);
                    }
                    case "--server" -> server = value(args, ++i, arg, words);
                    case "--allow" -> allow.add(Path.of(value(args, ++i, arg, words)));
                    case "--allow-read" -> read.add(Path.of(value(args, ++i, arg, words)));
                    case "--env" -> env.add(value(args, ++i, arg, words));
                    case "--name" -> name = value(args, ++i, arg, words);
                    case "--mxc" -> mxc = Path.of(value(args, ++i, arg, words));
                    // 已经在 language() 里查过
                    case "--lang" -> i++;
                    case "--allow-write" -> write = true;
                    case "--allow-shell" -> shell = true;
                    case "--no-network" -> network = false;
                    case "--insecure" -> insecure = true;
                    default -> throw new Refused(words.say(Key.REFUSED_UNKNOWN_OPTION, Map.of("option", arg)));
                }
            }
            if (allow.isEmpty()) {
                throw new Refused(words.say(Key.REFUSED_NO_ALLOW));
            }
            return new Options(server, List.copyOf(allow), write, shell, List.copyOf(read), List.copyOf(env), network, name, mxc, false, insecure);
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
     * 检查过的启动设置：WebSocket 地址、允许的目录、要发给服务器的 {@code hello}、令牌、跑命令的沙箱（没有 {@code --allow-shell}
     * 或不可用时是不能用的沙箱）。{@link #toString()} 不带令牌，打印或写进异常都不会泄露。
     */
    record Setup(URI socket, Options options, AllowedDirs dirs, Hello hello, String token, Sandbox sandbox) {

        @Override
        public String toString() {
            return "Setup[socket=" + socket + ", dirs=" + dirs.all() + ", write=" + hello.write() + "]";
        }
    }

    /** 检查目录、取令牌、检查沙箱、算出 {@code hello}。 */
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
        for (Path dir : options.read()) {
            // --allow-read 和 --allow 一样：要是目录、不能是家目录或根目录
            if (!Files.isDirectory(dir)) {
                throw new Refused(words.say(Key.REFUSED_READ_NOT_DIR, Map.of("dir", dir)));
            }
            try {
                refuseTooBroad(dir, env, words);
            } catch (IOException unreadable) {
                throw new Refused(words.say(Key.REFUSED_READ_NOT_DIR, Map.of("dir", dir)));
            }
        }
        for (String name : options.env()) {
            if (MxcPolicy.secret(name)) {
                throw new Refused(words.say(Key.REFUSED_ENV_SECRET, Map.of("name", name)));
            }
            if (!ENV_NAME.matcher(name).matches()) {
                throw new Refused(words.say(Key.REFUSED_ENV_NAME, Map.of("name", name)));
            }
        }
        String token = token(env, prompt, words);
        Sandbox sandbox = sandbox(options, dirs, env);
        return new Setup(socket, options, dirs, hello(options, dirs, sandbox.check()), token, sandbox);
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
     * 有 {@code --allow-shell} 时找 MXC 并检查。不可用时命令行不出现，原因写进启动说明和插件页，文件工具照常能用；
     * 不会退回到不隔离直接跑。
     */
    private static Sandbox sandbox(Options options, AllowedDirs dirs, Map<String, String> env) {
        if (!options.shell()) {
            return Sandbox.unavailable(Unavailable.of(Unavailable.Code.SHELL_NOT_ALLOWED));
        }
        String bundled = System.getProperty(BUNDLED_MXC);
        MxcPolicy policy = MxcPolicy.of(dirs, options.write(), options.read(), options.network(), options.env(), env);
        Sandbox sandbox = Mxc.open(options.mxc(), bundled == null || bundled.isBlank() ? null : Path.of(bundled), policy);
        sandbox.check();
        return sandbox;
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
    private static Hello hello(Options options, AllowedDirs dirs, Sandbox.Status shell) {
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
                Hello.Shell.of(shell), options.network(), git);
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

    /** 启动说明：连到哪、允许什么、命令行能不能用以及原因。 */
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
        Sandbox.Status shell = setup.sandbox().check();
        out.println(shell.available() ? words.say(Key.SUMMARY_SHELL_YES)
                : words.say(Key.SUMMARY_SHELL_NO, Map.of("reason", words.sandbox(shell.why()))));
        if (setup.sandbox() instanceof Mxc mxc && shell.available()) {
            out.println(words.say(Key.SUMMARY_SANDBOX, Map.of("program", mxc.program())));
        }
        if (setup.options().shell()) {
            out.println(words.say(hello.network() ? Key.SUMMARY_NETWORK_YES : Key.SUMMARY_NETWORK_NO));
            for (Path dir : setup.options().read()) {
                out.println(words.say(Key.SUMMARY_READ, Map.of("dir", dir)));
            }
            if (!setup.options().env().isEmpty()) {
                out.println(words.say(Key.SUMMARY_ENV, Map.of("names", String.join(", ", setup.options().env()))));
            }
        }
        out.println(words.say(Key.SUMMARY_FOOTER));
        out.flush();
    }
}
