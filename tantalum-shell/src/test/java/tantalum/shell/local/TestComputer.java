package tantalum.shell.local;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import tantalum.local.fs.AllowedDirs;
import tantalum.local.skill.Skills;
import tantalum.local.tool.LocalContext;
import tantalum.local.tool.LocalResult;
import tantalum.local.tool.LocalTool;
import tantalum.local.tool.Observations;
import tantalum.local.tool.Tools;
import tantalum.local.wiki.WikiPages;
import tantalum.local.wire.Browse;
import tantalum.local.wire.BrowseResult;
import tantalum.local.wire.Call;
import tantalum.local.wire.Cancel;
import tantalum.local.wire.Context;
import tantalum.local.wire.ContextResult;
import tantalum.local.wire.Hello;
import tantalum.local.wire.Message;
import tantalum.local.wire.Result;
import tantalum.local.wire.SkillLoad;
import tantalum.local.wire.SkillLoaded;
import tantalum.local.wire.Wire;
import tantalum.value.id.Principal;

/**
 * 测试用的电脑：不经过 WebSocket，直接接到 {@link LocalLinks} 上，用 {@code tantalum-local} 的真实工具在允许的目录里执行调用。
 * 和真的连接程序一样自己再查一遍改文件的权限（L4）。{@link #holdCalls()} 之后收到的调用停住不回，用来测“调用途中断开”。
 * 真的连接程序和 WebSocket 见 {@code tantalum-connect} 的 {@code LinkIT}。
 */
public final class TestComputer implements LocalLinks.Peer, AutoCloseable {

    private final Hello hello;
    private final AllowedDirs dirs;
    private final Map<String, Observations> observations = new ConcurrentHashMap<>();
    private final Map<String, Thread> running = new ConcurrentHashMap<>();
    private final BlockingQueue<Call> calls = new LinkedBlockingQueue<>();
    private final List<Context> contexts = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<SkillLoad> loads = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile CountDownLatch hold;
    private volatile boolean silentContext;
    private volatile LocalLinks.Link link;
    private volatile boolean closed;

    /**
     * 录下的命令结果（agent-eval 回放）：测试电脑没有沙箱，{@code bash} 不真的执行，按到达顺序回录制时的结果；为 null 时照旧拒绝。
     */
    private volatile java.util.Deque<LocalResult> recordedShell;

    /** 这台电脑上的假浏览器；没有时浏览器调用像没给 --allow-browser 一样被拒。 */
    private final tantalum.local.tool.Browser browser;

    private TestComputer(Hello hello, AllowedDirs dirs) {
        this(hello, dirs, null);
    }

    private TestComputer(Hello hello, AllowedDirs dirs, tantalum.local.tool.Browser browser) {
        this.hello = hello;
        this.dirs = dirs;
        this.browser = browser;
    }

    /**
     * 连一台带浏览器的电脑（local-browser）：{@code state} 是它在握手里说的浏览器情况，{@code browser} 是测试给的假浏览器
     * （收到查过参数的调用，回结果）。{@code browser} 为 null 时电脑这边像没给 {@code --allow-browser} 一样拒绝浏览器调用。
     */
    public static TestComputer connectWithBrowser(LocalLinks links, Principal user, Instant expiresAt, Path main, Hello.Browser state,
                                                  tantalum.local.tool.Browser browser) throws IOException {
        Path real = main.toRealPath();
        Hello hello = new Hello(Wire.PROTOCOL, "test", new Hello.Device("Test Mac", "macos", "26.2", "arm64"),
                List.of(new Hello.Root(real.toString())), true, new Hello.Shell(false, "--allow-shell was not given"), true, true, state);
        TestComputer computer = new TestComputer(hello, new AllowedDirs(List.of(real)), browser);
        computer.link = links.attach(user, expiresAt, System.nanoTime(), hello, computer)
                .orElseThrow(() -> new IllegalStateException("revoked"));
        return computer;
    }

    /**
     * 连一台什么都给的电脑，只为看服务器给模型的工具和提示（agent-eval 需求 3）：写、Git、命令行、浏览器都报“可用”。
     * 真的调用 {@code bash} 和浏览器会被这台电脑拒绝（它没有沙箱也没有浏览器）。
     */
    /** 按给定的 {@code hello} 连上（connector-jar 的端到端测试：协议 6、PowerShell、别的 jar）。允许的目录就是 {@code main}。 */
    public static TestComputer connectAs(LocalLinks links, Principal user, Instant expiresAt, Path main,
                                         java.util.function.Function<List<Hello.Root>, Hello> hello) throws IOException {
        Path real = main.toRealPath();
        Hello said = hello.apply(List.of(new Hello.Root(real.toString())));
        TestComputer computer = new TestComputer(said, new AllowedDirs(List.of(real)));
        computer.link = links.attach(user, expiresAt, System.nanoTime(), said, computer)
                .orElseThrow(() -> new IllegalStateException("revoked"));
        return computer;
    }

    public static TestComputer connectEverything(LocalLinks links, Principal user, Instant expiresAt, Path main) throws IOException {
        Path real = main.toRealPath();
        Hello hello = new Hello(Wire.PROTOCOL, "test", new Hello.Device("Test Mac", "macos", "26.2", "arm64"),
                List.of(new Hello.Root(real.toString())), true, new Hello.Shell(true, null), true, true,
                Hello.Browser.available("Google Chrome", false));
        TestComputer computer = new TestComputer(hello, new AllowedDirs(List.of(real)));
        computer.link = links.attach(user, expiresAt, System.nanoTime(), hello, computer)
                .orElseThrow(() -> new IllegalStateException("revoked"));
        return computer;
    }

    /**
     * 回放用的电脑（agent-eval 需求 2）：写和 Git 照录制时给，命令行报“可用”，{@code bash} 回 {@code shell} 里录下的结果
     * （按顺序；用完了再调用就报错）；其余工具全部真的执行。
     */
    public static TestComputer connectForReplay(LocalLinks links, Principal user, Instant expiresAt, Path main, boolean write,
                                                java.util.Deque<LocalResult> shell) throws IOException {
        Path real = main.toRealPath();
        Hello hello = new Hello(Wire.PROTOCOL, "test", new Hello.Device("Test Mac", "macos", "26.2", "arm64"),
                List.of(new Hello.Root(real.toString())), write, new Hello.Shell(true, null), true, true,
                Hello.Browser.unavailable(Hello.Browser.NOT_ALLOWED));
        TestComputer computer = new TestComputer(hello, new AllowedDirs(List.of(real)));
        computer.recordedShell = shell;
        computer.link = links.attach(user, expiresAt, System.nanoTime(), hello, computer)
                .orElseThrow(() -> new IllegalStateException("revoked"));
        return computer;
    }

    /** 把 {@code main}（第一个允许的目录）连给 {@code user}，到 {@code expiresAt} 为止。{@code git} 只是告诉服务器这台电脑装没装 git。 */
    public static TestComputer connect(LocalLinks links, Principal user, Instant expiresAt, Path main, boolean write, boolean git)
            throws IOException {
        return connect(links, user, expiresAt, main, write, git, Wire.PROTOCOL);
    }

    /** 同上，报 {@code protocol} 版本：2 的电脑像老连接程序一样不认会话目录和列子目录。 */
    public static TestComputer connect(LocalLinks links, Principal user, Instant expiresAt, Path main, boolean write, boolean git,
                                       int protocol) throws IOException {
        Path real = main.toRealPath();
        // 协议 5 起的连接程序总会说浏览器的情况；这台没给 --allow-browser。更老的没有这一项
        Hello hello = new Hello(protocol, "test", new Hello.Device("Test Mac", "macos", "26.2", "arm64"),
                List.of(new Hello.Root(real.toString())), write, new Hello.Shell(false, "--allow-shell was not given"), true, git,
                protocol >= 5 ? Hello.Browser.unavailable(Hello.Browser.NOT_ALLOWED) : null);
        TestComputer computer = new TestComputer(hello, new AllowedDirs(List.of(real)));
        computer.link = links.attach(user, expiresAt, System.nanoTime(), hello, computer)
                .orElseThrow(() -> new IllegalStateException("revoked"));
        return computer;
    }

    public Hello hello() {
        return hello;
    }

    /** 之后收到的调用都停住，直到断开（调用线程被打断）。之前收到的调用不再算进 {@link #nextCall}。 */
    public void holdCalls() {
        hold = new CountDownLatch(1);
        calls.clear();
    }

    /** 之后不回 {@code AGENTS.md}、清单和点名的请求，像电脑卡住了一样。 */
    public void ignoreContextRequests() {
        silentContext = true;
    }

    /** 重新开始回。 */
    public void answerContextRequests() {
        silentContext = false;
    }

    /** 收到过的清单请求，按到达顺序。 */
    public List<Context> contexts() {
        return List.copyOf(contexts);
    }

    /** 收到过的点名请求。 */
    public List<SkillLoad> loads() {
        return List.copyOf(loads);
    }

    /** 等下一个调用到达；超时返回 null。 */
    public Call nextCall(long seconds) throws InterruptedException {
        return calls.poll(seconds, TimeUnit.SECONDS);
    }

    /** 像网线断了一样：服务器那边按断开处理，这边结束在跑的调用。 */
    public void disconnect() {
        closed = true;
        running.values().forEach(Thread::interrupt);
        LocalLinks.Link current = link;
        if (current != null) {
            current.dropped();
        }
    }

    public boolean closed() {
        return closed;
    }

    @Override
    public void close() {
        disconnect();
    }

    @Override
    public void send(String text) throws IOException {
        if (closed) {
            throw new IOException("closed");
        }
        Message message = Wire.decode(text);
        switch (message) {
            case Call call -> {
                calls.add(call);
                running.put(call.id(), Thread.ofVirtual().name("test-computer-" + call.id()).start(() -> execute(call)));
            }
            case Cancel cancel -> {
                Thread thread = running.get(cancel.id());
                if (thread != null) {
                    thread.interrupt();
                }
            }
            case Context context -> {
                contexts.add(context);
                if (!silentContext) {
                    Thread.ofVirtual().start(() -> reply(new ContextResult(context.id(), agents(context.dir()).orElse(null),
                            context.skills() == null ? null : Skills.list(dirs, context.skills()),
                            context.wiki() == null ? null : WikiPages.list(dirs, context.wiki()))));
                }
            }
            case SkillLoad load -> {
                loads.add(load);
                if (!silentContext) {
                    Thread.ofVirtual().start(() -> reply(new SkillLoaded(load.id(), load.names().stream().map(name -> {
                        Skills.Loaded loaded = Skills.load(dirs, load.skills(), name, Skills.Caller.USER);
                        return new SkillLoaded.Loaded(name, loaded.content(), loaded.content() == null ? loaded.code() : null);
                    }).toList())));
                }
            }
            case Browse browse -> Thread.ofVirtual().start(() -> reply(browse(browse)));
            default -> { } // welcome、bye
        }
    }

    @Override
    public void ping() {
        LocalLinks.Link current = link;
        if (current != null) {
            current.pong();
        }
    }

    @Override
    public void close(int code, String reason) {
        disconnect();
    }

    private void execute(Call call) {
        try {
            CountDownLatch waiting = hold;
            if (waiting != null) {
                waiting.await();
            }
            reply(Result.of(call.id(), run(call)));
        } catch (InterruptedException cancelled) {
            // 取消或断开：不回结果
        } finally {
            running.remove(call.id());
        }
    }

    private LocalResult run(Call call) {
        Optional<LocalTool> tool = Tools.all().stream().filter(candidate -> candidate.name().equals(call.tool())).findFirst();
        if (tool.isEmpty()) {
            return LocalResult.error("Unknown tool: " + call.tool());
        }
        if (tool.get().kind() == LocalTool.Kind.WRITE && !hello.write()) {
            return LocalResult.error("This computer does not allow file changes.");
        }
        java.util.Deque<LocalResult> shell = recordedShell;
        if (tool.get().kind() == LocalTool.Kind.SHELL && shell != null) {
            LocalResult next = shell.pollFirst();
            return next != null ? next : LocalResult.error("replay: no recorded bash result left (the product ran more commands than were recorded)");
        }
        if (tool.get().kind() == LocalTool.Kind.BROWSER && browser == null) {
            return LocalResult.error(tantalum.local.tool.Browser.NOT_ALLOWED_TEXT);
        }
        AllowedDirs scoped;
        try {
            scoped = call.dir() == null ? dirs : dirs.from(call.dir());
        } catch (IllegalArgumentException notAllowed) {
            return LocalResult.error("directory not allowed on this computer: " + call.dir());
        }
        try {
            Observations seen = observations.computeIfAbsent(call.session(), ignored -> new Observations());
            return tool.get().invoke(new LocalContext(call.session(), scoped, seen).withSkills(call.skills()).withBrowser(browser),
                    call.arguments());
        } catch (Exception failure) {
            return LocalResult.error("Tool failed: " + failure);
        }
    }

    /** 和连接程序一样经 {@link AllowedDirs} 找主目录里的 {@code AGENTS.md}；这里不截断，服务器那边自己再截。 */
    private Optional<String> agents(String dir) {
        try {
            Path file = (dir == null ? dirs : dirs.from(dir)).resolve("AGENTS.md");
            return Files.isRegularFile(file) ? Optional.of(Files.readString(file, StandardCharsets.UTF_8)) : Optional.empty();
        } catch (IOException | IllegalArgumentException missing) {
            return Optional.empty();
        }
    }

    /** 同连接程序：列允许的目录本身，或一个目录下的子目录（排序、不含点开头的）。 */
    private BrowseResult browse(Browse request) {
        if (request.path() == null) {
            return new BrowseResult(request.id(), null, dirs.all().stream().map(Path::toString).toList(), false, null);
        }
        try (Stream<Path> listing = Files.list(dirs.resolve(request.path()))) {
            List<String> found = listing.filter(Files::isDirectory).filter(dir -> !dir.getFileName().toString().startsWith("."))
                    .map(Path::toString).sorted().toList();
            return new BrowseResult(request.id(), dirs.resolve(request.path()).toString(), found, false, null);
        } catch (IOException | IllegalArgumentException missing) {
            return new BrowseResult(request.id(), null, List.of(), false, AllowedDirs.NOT_FOUND + request.path());
        }
    }

    private void reply(Message message) {
        LocalLinks.Link current = link;
        if (current != null && !closed) {
            current.receive(message);
        }
    }
}
