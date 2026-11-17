package tantalum.local.shell;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 直接在这台电脑上跑命令（connector-jar 需求 4）。
 * <ul>
 *   <li>bash：{@code bash -c 命令}。</li>
 *   <li>PowerShell：{@code -NoLogo -NoProfile -NonInteractive -EncodedCommand}，命令前加一行把输出设成无 BOM 的 UTF-8
 *       （DSH {@code packages/shell/pwsh-local/src/index.ts:44-49} 用 {@code -Command} 加同样一行）。用 {@code -EncodedCommand}
 *       （UTF-16LE 的 Base64）而不是 {@code -Command}：Java 在 Windows 上拼命令行时不转义参数里的双引号，命令里有 {@code "} 就会被拆坏。</li>
 *   <li>输入一开始就关掉，标准输出和错误合在一起；太长时留头留尾，完整的存进 {@link Spill}（{@link Capture}）。</li>
 *   <li>到时或线程被打断时结束整个进程树：{@link ProcessHandle#descendants()} 逐个结束再结束 shell 本身，三个平台通用
 *       （pi 在 Windows 上用 {@code taskkill /F /T}，{@code utils/shell.js:157-188}；DSH 用 Job Object）。</li>
 * </ul>
 */
public final class LocalShell implements Shell {

    /** PowerShell 每条命令前的一行；放在第一行，命令的报错行号只差一行。 */
    static final String PREAMBLE = "[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false); $OutputEncoding = [Console]::OutputEncoding; ";

    private final ShellKind kind;
    private final Path program;
    private final Map<String, String> env;
    /** 放完整输出的目录；null 时只留头尾（测试、建不了临时目录时）。 */
    private final Spill spill;

    public LocalShell(ShellKind kind, Path program, Map<String, String> env, Spill spill) {
        this.kind = kind;
        this.program = program;
        this.env = Map.copyOf(env);
        this.spill = spill;
    }

    /** 按系统找 shell；找不到时返回不可用的，原因写明。 */
    public static Shell open(ShellFinder finder, ShellKind kind, Map<String, String> env) {
        return open(finder, kind, env, null);
    }

    /** 同上，太长的输出完整地存进 {@code spill}。 */
    public static Shell open(ShellFinder finder, ShellKind kind, Map<String, String> env, Spill spill) {
        return finder.find(kind)
                .<Shell>map(program -> new LocalShell(kind, program, env, spill))
                .orElseGet(() -> Shell.unavailable(kind, Unavailable.of(Unavailable.Code.NOT_FOUND, "shell",
                        kind == ShellKind.POWERSHELL ? "PowerShell (pwsh.exe or powershell.exe)" : "bash")));
    }

    /** 连接程序退出时删掉存完整输出的目录。 */
    @Override
    public void close() {
        if (spill != null) {
            spill.close();
        }
    }

    @Override
    public Status check() {
        return Status.ok();
    }

    @Override
    public ShellKind kind() {
        return kind;
    }

    @Override
    public Path program() {
        return program;
    }

    /** 起进程的命令行。 */
    List<String> commandLine(String command) {
        List<String> line = new ArrayList<>();
        line.add(program.toString());
        if (kind == ShellKind.POWERSHELL) {
            line.addAll(List.of("-NoLogo", "-NoProfile", "-NonInteractive", "-EncodedCommand", encode(PREAMBLE + command)));
        } else {
            line.addAll(List.of("-c", command));
        }
        return line;
    }

    static String encode(String script) {
        return Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
    }

    @Override
    public Run run(String command, Path workdir, Duration timeout) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(commandLine(command)).directory(workdir.toFile()).redirectErrorStream(true);
        builder.environment().clear();
        builder.environment().putAll(env);
        Process process = builder.start();
        try {
            process.getOutputStream().close();
            Capture capture = new Capture(spill);
            InputStream output = process.getInputStream();
            Thread reader = Thread.ofVirtual().name("shell-output").start(() -> drain(output, capture));
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                stop(process);
            }
            reader.join(Duration.ofSeconds(2));
            if (reader.isAlive()) {
                // 有进程逃出来还拿着输出管道：不再等它
                output.close();
                reader.join(Duration.ofSeconds(1));
            }
            return new Run(finished ? process.exitValue() : -1, capture.text(), !finished);
        } catch (InterruptedException stopped) {
            stop(process);
            throw stopped;
        } finally {
            if (process.isAlive() || process.descendants().findAny().isPresent()) {
                stop(process);
            }
        }
    }

    private static void drain(InputStream output, Capture capture) {
        byte[] buffer = new byte[8192];
        try (output) {
            for (int read = output.read(buffer); read >= 0; read = output.read(buffer)) {
                capture.write(buffer, 0, read);
            }
        } catch (IOException closed) {
            // 进程结束或这边关了管道
        }
    }

    /**
     * 结束整个进程树：先结束子孙进程（反复取，直到取不到新的），再结束 shell 本身。子孙先结束，shell 还活着，子孙的子孙就还挂在这棵树上。
     */
    public static void stop(Process process) throws InterruptedException {
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
}
