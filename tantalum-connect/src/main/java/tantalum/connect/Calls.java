package tantalum.connect;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import tantalum.connect.Journal.Outcome;
import tantalum.local.fs.AllowedDirs;
import tantalum.local.sandbox.Sandbox;
import tantalum.local.sandbox.Unavailable;
import tantalum.local.tool.LocalContext;
import tantalum.local.tool.LocalResult;
import tantalum.local.tool.LocalTool;
import tantalum.local.tool.Observations;
import tantalum.local.tool.ToolArguments;
import tantalum.local.tool.Tools;
import tantalum.local.wire.Call;
import tantalum.local.wire.Context;
import tantalum.local.wire.ContextResult;
import tantalum.local.wire.Result;

/**
 * 在这台电脑上执行服务器发来的调用。不信任服务器的判断，每个调用都查三件事
 * ：发给的用户是不是 {@code welcome} 里的这个人；工具类别是否被启动参数允许（改文件要 {@code --allow-write}，
 * 命令要 {@code --allow-shell}）；路径在不在允许的目录里（由工具经 {@link AllowedDirs} 查）。每个调用一个虚拟线程，
 * {@code cancel} 和断开时打断它。先读后写的记录只在内存里：连接程序重启后模型要先重新读。
 */
final class Calls {

    static final String OTHER_USER = "This computer is connected for another user.";
    static final String NO_WRITE = "This computer does not allow file changes.";
    static final String NO_SHELL = "This computer does not allow shell commands.";
    static final String AGENTS_FILE = "AGENTS.md";
    static final String DUPLICATE = "A call with this id is already running.";

    private final AllowedDirs dirs;
    private final boolean write;
    private final boolean shell;
    private final Sandbox sandbox;
    private final Journal journal;
    private final Map<String, LocalTool> tools;
    /**
     * 先读后写的记录。按会话分开记，所以每个会话一份；整台电脑共用一个实例，是为了让两个会话同时改同一个文件时也互斥。
     */
    private final Observations observations = new Observations();
    /** 在跑的调用。取消或断开时从这里拿走并打断；线程跑完时自己拿不到，就知道已被取消，不再回结果。 */
    private final Map<String, Thread> running = new ConcurrentHashMap<>();
    private volatile String user;

    /** {@code shell}：有 {@code --allow-shell} 而且 {@code sandbox} 检查通过。 */
    Calls(AllowedDirs dirs, boolean write, boolean shell, Sandbox sandbox, Journal journal) {
        this(dirs, write, shell, sandbox, journal, Tools.all());
    }

    /** 测试用：没有命令沙箱、换一套工具。 */
    Calls(AllowedDirs dirs, boolean write, boolean shell, Journal journal, List<LocalTool> tools) {
        this(dirs, write, shell, Sandbox.unavailable(Unavailable.of(Unavailable.Code.SHELL_NOT_ALLOWED)), journal, tools);
    }

    Calls(AllowedDirs dirs, boolean write, boolean shell, Sandbox sandbox, Journal journal, List<LocalTool> tools) {
        this.dirs = dirs;
        this.write = write;
        this.shell = shell && sandbox.check().available();
        this.sandbox = sandbox;
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
            case SHELL -> shell ? null : NO_SHELL;
            case READ, GIT -> null;
        };
    }

    private void execute(Call call, LocalTool tool, Consumer<Result> reply) {
        LocalResult result;
        Outcome outcome;
        try {
            result = tool.run(new LocalContext(call.session(), dirs, observations, sandbox), call.arguments());
            outcome = !result.error() ? Outcome.OK : outside(call) ? Outcome.DENIED : Outcome.ERROR;
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
    private boolean outside(Call call) {
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
     */
    ContextResult context(Context request) {
        try {
            Path file = dirs.resolve(AGENTS_FILE);
            return new ContextResult(request.id(), Files.isRegularFile(file) ? capped(file, ContextResult.MAX_AGENTS_BYTES) : null);
        } catch (IllegalArgumentException | IOException missing) {
            return new ContextResult(request.id(), null);
        }
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
