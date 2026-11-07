package tantalum.local.sandbox;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import tantalum.local.fs.PrivateFiles;

/**
 * 用微软 MXC 的 {@code mxc-exec-mac} 在 macOS 自带的 Seatbelt 里跑命令。
 * <ul>
 *   <li>{@link #check()}：先用 {@code --dry-run} 检查这台电脑实际要用的规则，再真跑一次 {@code /usr/bin/true}；任何一步失败都算不可用，
 *       原因写清楚。结果记下来，之后每次 {@link #run} 都先看它，不可用就不跑。</li>
 *   <li>{@link #run}：规则写进连接程序的私有目录（目录 700、文件 600，命令自己读不到）；每次调用一个临时目录当 {@code TMPDIR}，
 *       结束后连同规则文件删掉。标准输出和错误输出合在一起只留末尾（{@link Tail}）。</li>
 *   <li>超时交给 MXC 的 {@code process.timeout}：到期时它结束整个进程树，后台的 {@code sleep &} 也结束（实测）。</li>
 *   <li>取消（线程被打断）时自己结束进程树：直接结束 {@code mxc-exec-mac} 时它启动的进程会留下来（它们在自己的进程组里，实测），
 *       所以先取 {@link ProcessHandle#descendants()} 逐个结束，再结束 MXC 本身。照 pi 结束进程树（{@code CA/src/utils/shell.ts:220-264}）。</li>
 * </ul>
 */
public final class Mxc implements Sandbox {

    public static final String PROGRAM = "mxc-exec-mac";
    /** 检查时每一步最多等多久。 */
    static final Duration CHECK_TIMEOUT = Duration.ofSeconds(10);
    /** MXC 自己的超时到了之后再等多久；还没结束就由这边结束进程树。 */
    static final Duration GRACE = Duration.ofSeconds(10);
    /** MXC 超时时在错误输出最后打的一行，如 {@code {"error":{"code":"backend_error","message":"script timed out after 1500ms"}}}。 */
    private static final String TIMEOUT_LINE = "{\"error\":{\"code\":\"backend_error\",\"message\":\"script timed out";

    private final Path program;
    private final MxcPolicy policy;
    private final Path main;
    private final Path privateDir;
    private final Path home;
    private final Path calls;
    private volatile Status status;

    private Mxc(Path program, MxcPolicy policy, Path privateDir) throws IOException {
        this.program = program;
        this.policy = policy;
        this.main = policy.dirs().getFirst();
        this.privateDir = privateDir;
        this.home = Files.createDirectory(privateDir.resolve("sandbox-home"));
        this.calls = Files.createDirectory(privateDir.resolve("calls"));
    }

    /**
     * 找 {@code mxc-exec-mac} 并准备私有目录。{@code explicit} 是 {@code --mxc} 给的路径，给了就只用它；没给时用安装包里的
     * {@code bundled}（启动脚本经系统属性 {@code tantalum.mxc} 告诉程序）。找不到、不是 macOS 时返回不可用的沙箱，原因写清楚。
     * 返回的沙箱还没检查过，调用方接着调 {@link #check()}。
     */
    public static Sandbox open(Path explicit, Path bundled, MxcPolicy policy) {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) {
            return Sandbox.unavailable(Unavailable.of(Unavailable.Code.NOT_MACOS));
        }
        Path program;
        if (explicit != null) {
            if (!Files.isRegularFile(explicit) || !Files.isExecutable(explicit)) {
                return Sandbox.unavailable(Unavailable.of(Unavailable.Code.MXC_INVALID, "path", explicit.toString()));
            }
            program = explicit;
        } else if (bundled != null && Files.isRegularFile(bundled) && Files.isExecutable(bundled)) {
            program = bundled;
        } else {
            return Sandbox.unavailable(Unavailable.of(Unavailable.Code.MXC_MISSING));
        }
        try {
            return new Mxc(program.toRealPath(), policy, Files.createTempDirectory("tantalum-connect-"));
        } catch (IOException failure) {
            return Sandbox.unavailable(Unavailable.of(Unavailable.Code.TEMP_DIR, "detail", failure.getMessage()));
        }
    }

    public Path program() {
        return program;
    }

    @Override
    public Status check() {
        Status known = status;
        if (known != null) {
            return known;
        }
        synchronized (this) {
            if (status == null) {
                status = probe();
            }
            return status;
        }
    }

    private Status probe() {
        try {
            Outcome dry = execute(List.of("--dry-run"), "/usr/bin/true", main, CHECK_TIMEOUT);
            if (dry.exitCode() != 0) {
                return Status.unavailable(Unavailable.of(Unavailable.Code.CHECK_FAILED, "detail", firstLine(dry)));
            }
            Outcome real = execute(List.of(), "/usr/bin/true", main, CHECK_TIMEOUT);
            if (real.timedOut() || real.exitCode() != 0) {
                return Status.unavailable(Unavailable.of(Unavailable.Code.START_FAILED, "detail", firstLine(real)));
            }
            return Status.ok();
        } catch (IOException failure) {
            return Status.unavailable(Unavailable.of(Unavailable.Code.RUN_FAILED, "detail", failure.getMessage()));
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            return Status.unavailable(Unavailable.of(Unavailable.Code.CHECK_INTERRUPTED));
        }
    }

    private static String firstLine(Outcome outcome) {
        String text = outcome.output().text().strip();
        if (text.isEmpty()) {
            // 拼进 detail 原样显示，写英文，同 MXC 自己的输出
            return outcome.timedOut() ? "did not finish within " + CHECK_TIMEOUT.toSeconds() + " seconds" : "exit code " + outcome.exitCode();
        }
        String[] lines = text.split("\n");
        return lines[lines.length - 1].strip();
    }

    @Override
    public Run run(String command, Path workdir, Duration timeout) throws IOException, InterruptedException {
        Status current = check();
        if (!current.available()) {
            throw new IllegalStateException("The command sandbox is not available: " + current.reason());
        }
        Outcome outcome = execute(List.of(), command, workdir, timeout);
        return new Run(outcome.exitCode(), outcome.output(), outcome.timedOut());
    }

    private record Outcome(int exitCode, Tail.Text output, boolean timedOut) {}

    /** 写规则、起 MXC、读输出、等它结束；不论怎么结束都删掉规则文件和临时目录，并确认没有留下进程。 */
    private Outcome execute(List<String> flags, String command, Path workdir, Duration timeout) throws IOException, InterruptedException {
        Path temp = Files.createTempDirectory(calls, "run-");
        Path config = privateDir.resolve(temp.getFileName() + ".json");
        Process process = null;
        try {
            PrivateFiles.createFile(config);
            Files.writeString(config, policy.config(command, workdir, temp.toRealPath(), home.toRealPath(), timeout));
            List<String> line = new ArrayList<>();
            line.add(program.toString());
            line.addAll(flags);
            line.add(config.toString());
            ProcessBuilder builder = new ProcessBuilder(line).directory(privateDir.toFile()).redirectErrorStream(true)
                    .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
            // MXC 本来就不把自己的环境变量交给命令；这里连 MXC 自己也不给，令牌这类变量不出连接程序
            builder.environment().clear();
            long started = System.nanoTime();
            process = builder.start();
            Tail tail = new Tail();
            InputStream output = process.getInputStream();
            Thread reader = Thread.ofVirtual().name("mxc-output").start(() -> drain(output, tail));
            boolean finished = process.waitFor(timeout.plus(GRACE).toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                stop(process);
            }
            reader.join(Duration.ofSeconds(2));
            if (reader.isAlive()) {
                // 有进程逃出来还拿着输出管道：不再等它
                output.close();
                reader.join(Duration.ofSeconds(1));
            }
            int code = finished ? process.exitValue() : -1;
            boolean timedOut = !finished || (code != 0 && System.nanoTime() - started >= timeout.toNanos());
            Tail.Text text = tail.text();
            return new Outcome(code, timedOut ? withoutTimeoutLine(text) : text, timedOut);
        } catch (InterruptedException stopped) {
            if (process != null) {
                stop(process);
            }
            throw stopped;
        } finally {
            if (process != null && process.isAlive()) {
                stop(process);
            }
            Files.deleteIfExists(config);
            delete(temp);
        }
    }

    private static void drain(InputStream output, Tail tail) {
        byte[] buffer = new byte[8192];
        try (output) {
            for (int read = output.read(buffer); read >= 0; read = output.read(buffer)) {
                tail.write(buffer, 0, read);
            }
        } catch (IOException closed) {
            // 进程结束或这边关了管道
        }
    }

    /** MXC 超时时自己加的那行 JSON 不给模型看，换成“超时了”的说明（由调用方写）。 */
    private static Tail.Text withoutTimeoutLine(Tail.Text text) {
        String body = text.text();
        int last = body.lastIndexOf('\n');
        String tailLine = last < 0 ? body : body.substring(last + 1);
        if (!tailLine.startsWith(TIMEOUT_LINE)) {
            return text;
        }
        String kept = last < 0 ? "" : body.substring(0, last);
        return new Tail.Text(kept, text.truncated(), Math.max(0, text.totalLines() - 1), Math.max(0, text.shownLines() - 1));
    }

    /**
     * 结束整个进程树：先结束子孙进程（反复取，直到取不到新的），再结束 MXC 本身。子孙先结束，MXC 还活着，子孙的子孙就还挂在这棵树上。
     */
    static void stop(Process process) throws InterruptedException {
        for (int round = 0; round < 5; round++) {
            List<ProcessHandle> descendants = process.descendants().toList();
            if (descendants.isEmpty()) {
                break;
            }
            descendants.forEach(ProcessHandle::destroyForcibly);
            for (ProcessHandle handle : descendants) {
                handle.onExit().completeOnTimeout(handle, 1, TimeUnit.SECONDS).join();
            }
        }
        process.destroyForcibly();
        process.waitFor(2, TimeUnit.SECONDS);
    }

    /** 连接程序退出时删掉私有目录。 */
    @Override
    public void close() {
        delete(privateDir);
    }

    /** 尽量删干净：命令可能在临时目录里留下没有写权限的目录，先把权限加回来再删；删不掉的留给 {@link #close()}。 */
    private static void delete(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException locked) {
                    path.getParent().toFile().setWritable(true, true);
                    path.toFile().delete();
                }
            }
        } catch (IOException | java.io.UncheckedIOException unreadable) {
            // 留给 close()
        }
    }
}
