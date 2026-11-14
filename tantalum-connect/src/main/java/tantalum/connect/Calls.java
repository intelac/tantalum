package tantalum.connect;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import tantalum.connect.Journal.Outcome;
import tantalum.local.fs.AllowedDirs;
import tantalum.local.shell.Shell;
import tantalum.local.shell.Unavailable;
import tantalum.local.tool.Browser;
import tantalum.local.tool.LocalContext;
import tantalum.local.tool.LocalResult;
import tantalum.local.tool.LocalTool;
import tantalum.local.skill.Skills;
import tantalum.local.tool.Observations;
import tantalum.local.tool.SkillTool;
import tantalum.local.tool.ToolArguments;
import tantalum.local.tool.ShellTool;
import tantalum.local.tool.Tools;
import tantalum.local.wire.Browse;
import tantalum.local.wire.BrowseResult;
import tantalum.local.wire.Call;
import tantalum.local.wire.Context;
import tantalum.local.wire.ContextResult;
import tantalum.local.wire.Result;
import tantalum.local.wire.SkillList;
import tantalum.local.wire.SkillLoad;
import tantalum.local.wire.SkillLoaded;
import tantalum.local.wire.WikiList;
import tantalum.local.wiki.WikiPages;
import tantalum.value.json.Json;

/**
 * 在这台电脑上执行服务器发来的调用。不信任服务器的判断，每个调用都查三件事
 * ：发给的用户是不是 {@code welcome} 里的这个人；工具类别是否被启动参数允许（改文件要 {@code --allow-write}，
 * 命令要 {@code --allow-shell}，浏览器要 {@code --allow-browser}）；路径在不在允许的目录里（由工具经 {@link AllowedDirs} 查）。每个调用一个虚拟线程，
 * {@code cancel} 和断开时打断它。先读后写的记录只在内存里：连接程序重启后模型要先重新读。
 */
final class Calls {

    static final String OTHER_USER = "This computer is connected for another user.";
    static final String NO_WRITE = "This computer does not allow file changes.";
    static final String NO_SHELL = "This computer does not allow shell commands.";
    static final String NO_BROWSER = Browser.NOT_ALLOWED_TEXT;
    static final String AGENTS_FILE = "AGENTS.md";
    /** 会话目录不在这台电脑允许的范围里（或不存在）：工具不跑，也不退回主目录。 */
    static final String DIR_NOT_ALLOWED = "directory not allowed on this computer: ";
    static final String DUPLICATE = "A call with this id is already running.";

    private final AllowedDirs dirs;
    private final boolean write;
    private final boolean shell;
    private final Shell commands;
    private final Journal journal;
    /** 这台电脑上的浏览器；没允许或没找到时为 null。 */
    private final Browser browser;
    private final Map<String, LocalTool> tools;
    /**
     * 先读后写的记录。按会话分开记，所以每个会话一份；整台电脑共用一个实例，是为了让两个会话同时改同一个文件时也互斥。
     */
    private final Observations observations = new Observations();
    /** 在跑的调用。取消或断开时从这里拿走并打断；线程跑完时自己拿不到，就知道已被取消，不再回结果。 */
    private final Map<String, Thread> running = new ConcurrentHashMap<>();
    private volatile String user;

    /** {@code shell}：有 {@code --allow-shell} 而且电脑上找到了 shell（{@code commands} 可用）。 */
    Calls(AllowedDirs dirs, boolean write, boolean shell, Shell commands, Journal journal) {
        this(dirs, write, shell, commands, journal, Tools.all());
    }

    /** 测试用：不能跑命令、换一套工具。 */
    Calls(AllowedDirs dirs, boolean write, boolean shell, Journal journal, List<LocalTool> tools) {
        this(dirs, write, shell, Shell.unavailable(Unavailable.of(Unavailable.Code.SHELL_NOT_ALLOWED)), journal, tools);
    }

    Calls(AllowedDirs dirs, boolean write, boolean shell, Shell commands, Journal journal, List<LocalTool> tools) {
        this(dirs, write, shell, commands, journal, tools, null);
    }

    /** {@code browser}：有 {@code --allow-browser} 而且找到了浏览器时才有，否则为 null，浏览器调用一律拒绝。 */
    Calls(AllowedDirs dirs, boolean write, boolean shell, Shell commands, Journal journal, Browser browser) {
        this(dirs, write, shell, commands, journal, Tools.all(), browser);
    }

    Calls(AllowedDirs dirs, boolean write, boolean shell, Shell commands, Journal journal, List<LocalTool> tools, Browser browser) {
        this.browser = browser;
        this.dirs = dirs;
        this.write = write;
        this.shell = shell && commands.check().available();
        this.commands = commands;
        this.journal = journal;
        this.tools = tools.stream().collect(Collectors.toUnmodifiableMap(LocalTool::name, tool -> tool));
    }

    /** {@code welcome} 里的用户：之后的调用都必须是发给这个用户的。 */
    void welcome(String user) {
        this.user = user;
    }

    /** 收到一个调用：检查，通过就在虚拟线程上执行，结果交给 {@code reply}。被拒的调用立即回错误。 */
    void call(Call call, Consumer<Result> reply) {
        String refusal = refusal(call);
        if (refusal != null) {
            journal.call(call.session(), call.tool(), call.arguments(), Outcome.DENIED);
            reply.accept(new Result(call.id(), refusal, true, List.of()));
            return;
        }
        LocalTool tool = tools.get(call.tool());
        Thread worker = Thread.ofVirtual().name("call-" + call.id()).unstarted(() -> execute(call, tool, reply));
        if (running.putIfAbsent(call.id(), worker) != null) {
            // 同一个 id 还在跑：不悄悄丢掉，回一个错误，服务器那边等的人知道这次没执行
            reply.accept(new Result(call.id(), DUPLICATE, true, List.of()));
            return;
        }
        worker.start();
    }

    /** 拒绝的原因，没问题时为 null。工具不认识也在这里拒。 */
    private String refusal(Call call) {
        if (user == null || !user.equals(call.user())) {
            return OTHER_USER;
        }
        LocalTool tool = tools.get(call.tool());
        if (tool == null) {
            return "Unknown tool: " + call.tool();
        }
        return switch (tool.kind()) {
            case WRITE -> write ? null : NO_WRITE;
            // 另一种 shell 的工具（mac 上来了 powershell）也拒：服务器按 hello 只给一种，这里再核对一遍
            case SHELL -> shell && tool instanceof ShellTool shellTool && shellTool.shell() == commands.kind() ? null : NO_SHELL;
            case READ, GIT, SKILL -> null;
            case BROWSER -> browser != null ? null : NO_BROWSER;
        };
    }

    private void execute(Call call, LocalTool tool, Consumer<Result> reply) {
        LocalResult result;
        Outcome outcome;
        try {
            AllowedDirs scoped = call.dir() == null ? dirs : sessionDirs(call.dir());
            if (scoped == null) {
                result = LocalResult.error(DIR_NOT_ALLOWED + call.dir());
                outcome = Outcome.DENIED;
            } else {
                result = tool.invoke(new LocalContext(call.session(), scoped, observations, commands, call.skills(), browser), call.arguments());
                outcome = !result.error() ? Outcome.OK : outside(call, scoped) ? Outcome.DENIED : Outcome.ERROR;
            }
        } catch (InterruptedException stopped) {
            result = null;
            outcome = Outcome.CANCELLED;
        } catch (Throwable failure) {
            // 发给服务器的是固定文字：本机的异常消息可能带路径；细节只在本地终端。
            // Error 也接住：否则这个 id 永远留在 running 里，服务器那边等到超时
            System.err.println("[tantalum-connect] " + call.tool() + " failed: " + failure);
            result = LocalResult.error("Tool failed on the computer; see the connector's output.");
            outcome = Outcome.ERROR;
        }
        boolean cancelled = running.remove(call.id()) == null;
        if (cancelled || result == null) {
            journal.call(call.session(), call.tool(), call.arguments(), Outcome.CANCELLED);
            return;
        }
        journal.call(call.session(), call.tool(), call.arguments(), outcome);
        try {
            reply.accept(Result.of(call.id(), result));
        } catch (RuntimeException failure) {
            System.err.println("[tantalum-connect] could not send the result of " + call.tool() + ": " + failure.getClass().getSimpleName());
        }
    }

    /**
     * 记录用：报错是不是因为路径在允许的目录外。给服务器的报错不区分越界和不存在，终端是用户自己看的，分得清楚些。
     */
    private boolean outside(Call call, AllowedDirs dirs) {
        String path = ToolArguments.string(call.arguments(), "path");
        if (path == null) {
            return false;
        }
        Path candidate;
        try {
            candidate = dirs.main().resolve(path).normalize();
        } catch (InvalidPathException unusable) {
            return false;
        }
        Path real;
        try {
            real = candidate.toRealPath();
        } catch (IOException | SecurityException missing) {
            real = candidate;
        }
        for (Path dir : dirs.all()) {
            if (real.startsWith(dir)) {
                return false;
            }
        }
        return true;
    }

    /** 服务器不要这次调用的结果了：打断它；是命令时工具自己结束进程树。 */
    void cancel(String id) {
        Thread worker = running.remove(id);
        if (worker != null) {
            worker.interrupt();
        }
    }

    /** 连接断了：这条连接上在跑的调用全部打断。 */
    void stopAll() {
        for (String id : List.copyOf(running.keySet())) {
            cancel(id);
        }
    }

    int running() {
        return running.size();
    }

    /**
     * 主目录里的 {@code AGENTS.md}，最多 {@value ContextResult#MAX_AGENTS_BYTES} 字节，截断的说明和服务器上原来的一样。
     * 也走 {@link AllowedDirs#resolve}：指向允许目录外的符号链接不会被读。没有或读不了时为 null。
     * 请求里带了技能目录、wiki 目录时（协议 4 起）把那两个目录里有什么一起带回去，各在终端记一行。
     */
    ContextResult context(Context request) {
        SkillList skills = null;
        if (request.skills() != null) {
            skills = Skills.list(dirs, request.skills());
            journal.listed("skills", request.skills(), skills.state(), skills.items().size());
        }
        WikiList wiki = null;
        if (request.wiki() != null) {
            wiki = WikiPages.list(dirs, request.wiki());
            journal.listed("wiki", request.wiki(), wiki.state(), wiki.total());
        }
        return new ContextResult(request.id(), agents(request), skills, wiki);
    }

    private String agents(Context request) {
        AllowedDirs scoped = request.dir() == null ? dirs : sessionDirs(request.dir());
        if (scoped == null) {
            return null;
        }
        try {
            Path file = scoped.resolve(AGENTS_FILE);
            return Files.isRegularFile(file) ? capped(file, ContextResult.MAX_AGENTS_BYTES) : null;
        } catch (IllegalArgumentException | IOException missing) {
            return null;
        }
    }

    /**
     * 用户在消息里点名的技能：每个当场读，和模型用 {@code skill} 工具加载的是同一份文字。没有这个技能、它不让用户点名时两样都不带，
     * 服务器把那个词当普通文字。每个名字在终端记一行。
     */
    SkillLoaded skillLoad(SkillLoad request) {
        List<SkillLoaded.Loaded> items = new ArrayList<>();
        for (String name : request.names().subList(0, Math.min(request.names().size(), SkillLoad.MAX_NAMES))) {
            Skills.Loaded loaded = Skills.load(dirs, request.skills(), name, Skills.Caller.USER);
            Json arguments = new Json.Obj(Map.of("name", new Json.Str(name)));
            if (loaded.content() != null) {
                journal.call(request.session(), SkillTool.NAME, arguments, Outcome.OK);
                items.add(new SkillLoaded.Loaded(name, loaded.content(), null));
            } else if (loaded.failure() == Skills.Failure.BROKEN) {
                journal.call(request.session(), SkillTool.NAME, arguments, Outcome.ERROR);
                items.add(new SkillLoaded.Loaded(name, null, loaded.code()));
            } else {
                items.add(new SkillLoaded.Loaded(name, null, null));
            }
        }
        return new SkillLoaded(request.id(), items);
    }

    /** 会话目录做起点的允许目录；目录不存在或不在允许的范围里时为 null。 */
    private AllowedDirs sessionDirs(String dir) {
        try {
            return dirs.from(dir);
        } catch (IllegalArgumentException unusable) {
            return null;
        }
    }

    /**
     * 添加工作区时列子目录：{@code path} 为空时列允许的目录本身，否则列它下面的目录（不跟符号链接、不列 {@code .} 开头的），
     * 按名字排序，最多 {@value BrowseResult#MAX_DIRS} 个。越界和不存在说同一句话。只读目录名，不读文件，不记终端。
     */
    BrowseResult browse(Browse request) {
        if (request.path() == null) {
            return new BrowseResult(request.id(), null, dirs.all().stream().map(Path::toString).toList(), false, null);
        }
        Path real;
        try {
            real = dirs.resolve(request.path());
        } catch (IllegalArgumentException unusable) {
            return new BrowseResult(request.id(), null, List.of(), false, AllowedDirs.NOT_FOUND + request.path());
        }
        if (!Files.isDirectory(real)) {
            return new BrowseResult(request.id(), null, List.of(), false, AllowedDirs.NOT_FOUND + request.path());
        }
        List<Path> children = new ArrayList<>();
        try (Stream<Path> listing = Files.list(real)) {
            listing.filter(child -> !child.getFileName().toString().startsWith("."))
                    .filter(child -> Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS))
                    .forEach(children::add);
        } catch (IOException | SecurityException unreadable) {
            return new BrowseResult(request.id(), real.toString(), List.of(), false, "cannot list " + request.path());
        }
        children.sort(Comparator.comparing(child -> child.getFileName().toString(), String.CASE_INSENSITIVE_ORDER));
        boolean truncated = children.size() > BrowseResult.MAX_DIRS;
        List<String> shown = children.stream().limit(BrowseResult.MAX_DIRS).map(Path::toString).toList();
        return new BrowseResult(request.id(), real.toString(), shown, truncated, null);
    }

    /** 同服务器 {@code ContextHandler.capped}：超限时按 UTF-8 边界截断并告诉模型。 */
    static String capped(Path file, int maxBytes) throws IOException {
        byte[] bytes;
        try (InputStream in = Files.newInputStream(file)) {
            bytes = in.readNBytes(maxBytes + 1);
        }
        if (bytes.length <= maxBytes) {
            return new String(bytes, StandardCharsets.UTF_8).strip();
        }
        int cut = maxBytes;
        while (cut > 0 && (bytes[cut] & 0xC0) == 0x80) {
            cut--;
        }
        return new String(bytes, 0, cut, StandardCharsets.UTF_8).strip()
                + "\n\n(This file was truncated at " + maxBytes + " bytes.)";
    }
}
