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
import tantalum.local.fs.AllowedDirs;
import tantalum.local.tool.GitRepo;
import tantalum.local.wire.Hello;
import tantalum.local.wire.Wire;

/**
 * 连接程序的入口（local-workspace 设计“tantalum.connect.Main”）。允许什么只由这里的启动参数决定（L4）；令牌只从环境变量
 * {@code TANTALUM_TOKEN} 或不回显的终端输入读，不从命令行参数读，也不写进任何文件（L2）。提示用简单中文。
 */
public final class Main {

    static final String TOKEN_VARIABLE = "TANTALUM_TOKEN";
    static final String TOKEN_PREFIX = "tc_";
    static final String DEFAULT_SERVER = "http://127.0.0.1:8091";
    static final String PATH = "/api/local/connect";
    /** 启动被拒（参数不对、目录不存在、没有令牌）。 */
    static final int REFUSED = 1;
    /** 这一步还没有命令沙箱（阶段 4 用 MXC 实现），有 {@code --allow-shell} 也如实报不可用。 */
    static final String NO_SANDBOX_YET = "the command sandbox is not built yet";
    static final String NO_SHELL_FLAG = "--allow-shell was not given";
    /** 参数里夹着一整个令牌（如 {@code --allow=tc_…}）。令牌的随机部分是 43 个 base64url 字符。 */
    private static final Pattern EMBEDDED_TOKEN = Pattern.compile(TOKEN_PREFIX + "[A-Za-z0-9_-]{40,}");

    private static final String HELP = """
            用法：tantalum-connect --allow 目录 [--allow 目录 ...] [选项]

            把这台电脑上允许的目录交给 Tantalum 服务器上的 agent 使用。令牌在网页的插件页生成，
            启动前放进环境变量 TANTALUM_TOKEN，或者启动后按提示粘贴（不显示）。不要把令牌写在命令行上。

              --server URL      服务器地址，默认 http://127.0.0.1:8091
              --allow 目录      允许 agent 进的目录，可以写多个，第一个是主目录（至少一个）
              --allow-write     允许改文件（write_file、edit_file）。不加就只能读
              --allow-shell     允许跑命令（bash，在系统沙箱里执行）
              --allow-read 目录 命令额外能读的目录，可以写多个
              --env 名字        把这个环境变量交给命令，可以写多个；名字里有 KEY、TOKEN、SECRET、PASSWORD 的不行
              --no-network      命令不能联网
              --name 名字       在网页上显示的电脑名，默认主机名
              --mxc 路径        MXC 沙箱程序 mxc-exec-mac 的位置
              --help            显示这段说明

            风险：服务器那边的 agent 能读允许目录里的所有文件；加了 --allow-write 能改、能删其中的内容。
            每个操作都会在这个终端里打一行记录。按 Ctrl+C 断开。

            退出码：1 启动参数不对；2 服务器拒绝或结束了连接（令牌无效、到期、撤销，或被另一台电脑替换）。
            """;

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
        Setup setup;
        try {
            Options options = Options.parse(args);
            if (options.help()) {
                out.print(HELP);
                return 0;
            }
            setup = setup(options, env, prompt);
        } catch (Refused refused) {
            out.println(refused.getMessage());
            return REFUSED;
        }
        summary(setup, out);
        Journal journal = new Journal(out, Clock.systemDefaultZone());
        Calls calls = new Calls(setup.dirs(), setup.hello().write(), setup.hello().shell().available(), journal);
        // Ctrl+C：先打断在跑的调用，git 这类子进程跟着结束
        Runtime.getRuntime().addShutdownHook(new Thread(calls::stopAll, "stop-calls"));
        try {
            return new Link(setup, calls, out).run();
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

    /** 启动参数不对。消息直接给用户看，不含令牌。 */
    static final class Refused extends Exception {

        Refused(String message) {
            super(message);
        }
    }

    /** 解析后的启动参数。 */
    record Options(String server, List<Path> allow, boolean write, boolean shell, List<Path> read, List<String> env,
                   boolean network, String name, Path mxc, boolean help) {

        static Options parse(String[] args) throws Refused {
            for (String arg : args) {
                if (looksLikeToken(arg)) {
                    throw new Refused("不要把令牌写在命令行上：同一台电脑上的其他程序能看到命令行参数。"
                            + "请改用环境变量 " + TOKEN_VARIABLE + "，或者不带令牌启动，再按提示粘贴。");
                }
            }
            String server = DEFAULT_SERVER;
            List<Path> allow = new ArrayList<>();
            List<Path> read = new ArrayList<>();
            List<String> env = new ArrayList<>();
            boolean write = false;
            boolean shell = false;
            boolean network = true;
            String name = null;
            Path mxc = null;
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--help", "-h" -> {
                        return new Options(server, List.of(), false, false, List.of(), List.of(), true, null, null, true);
                    }
                    case "--server" -> server = value(args, ++i, arg);
                    case "--allow" -> allow.add(Path.of(value(args, ++i, arg)));
                    case "--allow-read" -> read.add(Path.of(value(args, ++i, arg)));
                    case "--env" -> env.add(value(args, ++i, arg));
                    case "--name" -> name = value(args, ++i, arg);
                    case "--mxc" -> mxc = Path.of(value(args, ++i, arg));
                    case "--allow-write" -> write = true;
                    case "--allow-shell" -> shell = true;
                    case "--no-network" -> network = false;
                    default -> throw new Refused("不认识的参数：" + arg + "。用 --help 查看全部参数。");
                }
            }
            if (allow.isEmpty()) {
                throw new Refused("至少要用 --allow 给一个目录，例如 --allow ~/work/shop。用 --help 查看全部参数。");
            }
            return new Options(server, List.copyOf(allow), write, shell, List.copyOf(read), List.copyOf(env), network, name, mxc, false);
        }

        /** 以 {@code tc_} 开头、在哪里带着一整个令牌、或者是 {@code --token}。报错时不回显这个参数。 */
        static boolean looksLikeToken(String arg) {
            return arg.startsWith(TOKEN_PREFIX) || arg.startsWith("--token") || EMBEDDED_TOKEN.matcher(arg).find();
        }

        private static String value(String[] args, int at, String option) throws Refused {
            if (at >= args.length || args[at].startsWith("--")) {
                throw new Refused(option + " 后面要跟一个值。");
            }
            return args[at];
        }
    }

    /**
     * 检查过的启动设置：WebSocket 地址、允许的目录、要发给服务器的 {@code hello}、令牌。
     * {@link #toString()} 不带令牌，打印或写进异常都不会泄露。
     */
    record Setup(URI socket, Options options, AllowedDirs dirs, Hello hello, String token) {

        @Override
        public String toString() {
            return "Setup[socket=" + socket + ", dirs=" + dirs.all() + ", write=" + hello.write() + "]";
        }
    }

    /** 检查目录、取令牌、算出 {@code hello}。 */
    static Setup setup(Options options, Map<String, String> env, Prompt prompt) throws Refused {
        URI socket = socket(options.server());
        AllowedDirs dirs;
        try {
            for (Path dir : options.allow()) {
                if (!Files.isDirectory(dir)) {
                    throw new Refused("--allow 的目录不存在或不是目录：" + dir);
                }
            }
            dirs = new AllowedDirs(options.allow());
        } catch (IOException | IllegalArgumentException failure) {
            throw new Refused("--allow 的目录不能用：" + failure.getMessage());
        }
        for (Path dir : options.read()) {
            if (!Files.isDirectory(dir)) {
                throw new Refused("--allow-read 的目录不存在或不是目录：" + dir);
            }
        }
        for (String name : options.env()) {
            String upper = name.toUpperCase(Locale.ROOT);
            if (upper.contains("KEY") || upper.contains("TOKEN") || upper.contains("SECRET") || upper.contains("PASSWORD")) {
                throw new Refused("--env 不能放行 " + name + "：名字里有 KEY、TOKEN、SECRET 或 PASSWORD 的变量可能是密钥，不交给命令。");
            }
        }
        Hello hello = hello(options, dirs);
        return new Setup(socket, options, dirs, hello, token(env, prompt));
    }

    private static URI socket(String server) throws Refused {
        try {
            URI uri = new URI(server.strip());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String socketScheme = switch (scheme) {
                case "http", "ws" -> "ws";
                case "https", "wss" -> "wss";
                default -> throw new Refused("--server 要以 http:// 或 https:// 开头：" + server);
            };
            if (uri.getHost() == null) {
                throw new Refused("--server 的地址不对：" + server);
            }
            String base = uri.getPath() == null ? "" : uri.getPath().replaceAll("/+$", "");
            return new URI(socketScheme, null, uri.getHost(), uri.getPort(), base + PATH, null, null);
        } catch (URISyntaxException bad) {
            throw new Refused("--server 的地址不对：" + server);
        }
    }

    /** 令牌：环境变量优先，没有就问。只检查长相，不打印内容。 */
    private static String token(Map<String, String> env, Prompt prompt) throws Refused {
        String token = env.get(TOKEN_VARIABLE);
        if (token == null || token.isBlank()) {
            char[] typed = prompt.secret("粘贴网页上生成的令牌（不显示），按回车：");
            if (typed == null) {
                throw new Refused("没有令牌。请把网页上生成的令牌放进环境变量 " + TOKEN_VARIABLE + " 再启动。");
            }
            token = new String(typed);
            Arrays.fill(typed, '\0');
        }
        token = token.strip();
        if (!token.startsWith(TOKEN_PREFIX) || token.length() > 200 || !token.matches("[A-Za-z0-9_-]+")) {
            throw new Refused("这不像 Tantalum 的令牌（应以 " + TOKEN_PREFIX + " 开头）。请在网页的插件页重新复制。");
        }
        return token;
    }

    private static Hello hello(Options options, AllowedDirs dirs) {
        List<Hello.Root> roots = new ArrayList<>();
        for (Path dir : dirs.all()) {
            boolean git;
            try {
                git = GitRepo.at(dirs, dir);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                git = false;
            }
            roots.add(new Hello.Root(dir.toString(), git));
        }
        Hello.Shell shell = options.shell() ? new Hello.Shell(false, NO_SANDBOX_YET) : new Hello.Shell(false, NO_SHELL_FLAG);
        return new Hello(Wire.PROTOCOL, version(), device(options.name()), roots, options.write(), shell, options.network());
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
    static void summary(Setup setup, PrintStream out) {
        Hello hello = setup.hello();
        out.println("Tantalum Connect " + hello.connector());
        out.println("服务器：" + setup.options().server().strip());
        out.println("电脑名：" + hello.device().name() + "（" + hello.device().os() + " " + hello.device().osVersion() + " "
                + hello.device().arch() + "）");
        for (int i = 0; i < hello.roots().size(); i++) {
            Hello.Root root = hello.roots().get(i);
            out.println((i == 0 ? "主目录：" : "允许的目录：") + root.path() + (root.git() ? "（Git 仓库）" : ""));
        }
        out.println("改文件：" + (hello.write() ? "允许" : "不允许（要允许请加 --allow-write）"));
        out.println("命令行：" + (hello.shell().available() ? "可用" : "不可用，" + hello.shell().reason()));
        if (setup.options().shell()) {
            out.println("命令联网：" + (hello.network() ? "允许" : "不允许"));
            for (Path dir : setup.options().read()) {
                out.println("命令额外能读：" + dir);
            }
            if (!setup.options().env().isEmpty()) {
                out.println("交给命令的环境变量：" + String.join(", ", setup.options().env()));
            }
        }
        out.println("每个操作会在下面打一行记录。按 Ctrl+C 断开。");
        out.flush();
    }
}
