package tantalum.local.tool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import tantalum.local.shell.Shell;
import tantalum.local.shell.ShellKind;
import tantalum.local.shell.Tail;
import tantalum.local.tool.ToolArguments.Param;
import tantalum.value.json.Json;

/**
 * 在用户电脑上跑一条命令：macOS、Linux 上 {@code bash}，Windows 上 {@code powershell}，按系统只给一个（connector-jar 需求 4，
 * 同 DSH 按平台只装一个）。参数 {@code command}、{@code timeout}（秒，默认 120，最多 600，照 DSH {@code shell/bash-local/src/index.ts:102-106}，
 * 超过上限按上限算）、{@code workdir}（默认主目录，必须在允许的目录里）。
 * 标准输出和错误输出合在一起，只留最后 2000 行或 50 KB 并写明截断（照 pi {@code CA/src/core/tools/bash.ts:283}）；
 * 退出码不是 0 算错误结果，写明退出码。超时、取消、断开时结束整个进程树（{@link Shell#run}）。没有沙箱，执行前由服务器上的审批问用户。
 */
public abstract sealed class ShellTool implements LocalTool permits ShellTool.Bash, ShellTool.PowerShell {

    static final int DEFAULT_TIMEOUT = 120;
    static final int MAX_TIMEOUT = 600;
    /** 服务器多等一会儿，让电脑上的超时先到，超时的说明和输出能送回来。 */
    static final Duration REPLY_MARGIN = Duration.ofSeconds(15);

    /** 这个工具对应的 shell；连接程序用它确认电脑上的 shell 和工具是同一种。 */
    public abstract ShellKind shell();

    /** 说明的共同部分：没有沙箱、通常先问、输出截断、退出码。 */
    static String common() {
        return " The command runs as the user, without a sandbox, so it can reach any file the user's account can. The user is "
                + "usually asked to approve each command before it runs and may deny it; if denied, do not retry it another way. "
                + "Returns stdout and stderr together; output is truncated to the last " + Tail.MAX_LINES + " lines or "
                + Tail.MAX_BYTES / 1024 + " KB. A non-zero exit code is an error. Prefer read_file, grep and edit_file for reading "
                + "and changing files.";
    }

    /** macOS、Linux 上的 {@code bash}。 */
    public static final class Bash extends ShellTool {
        @Override public String name() { return "bash"; }
        @Override public ShellKind shell() { return ShellKind.BASH; }
        @Override public String description() {
            return "Run a bash command on the user's computer, in the main allowed directory or workdir." + common();
        }
        @Override String commandHint() { return "The bash command to run."; }
    }

    /** Windows 上的 {@code powershell}（pwsh 7 或系统自带的 5.1）。 */
    public static final class PowerShell extends ShellTool {
        @Override public String name() { return "powershell"; }
        @Override public ShellKind shell() { return ShellKind.POWERSHELL; }
        @Override public String description() {
            return "Run a PowerShell command on the user's Windows computer, in the main allowed directory or workdir. Use "
                    + "PowerShell syntax: cmdlets such as Get-ChildItem and Select-String, $env:NAME for environment variables, ';' "
                    + "to run commands one after another." + common();
        }
        @Override String commandHint() { return "The PowerShell command to run."; }
    }

    abstract String commandHint();

    @Override public Kind kind() { return Kind.SHELL; }
    @Override public Json schema() {
        return ToolArguments.schema(List.of(
                Param.required("command", "string", commandHint()),
                Param.optional("timeout", "integer", "Seconds before the command and everything it started are stopped. Default "
                        + DEFAULT_TIMEOUT + ", at most " + MAX_TIMEOUT + "."),
                Param.optional("workdir", "string", "Working directory, relative to the main directory or absolute. Default: the "
                        + "main directory.")));
    }

    /** 命令自己最长跑 {@code timeout} 秒；服务器在这之上多等一会儿。 */
    @Override
    public Duration timeout(Json arguments, Duration standard) {
        try {
            return Duration.ofSeconds(seconds(arguments)).plus(REPLY_MARGIN);
        } catch (IllegalArgumentException invalid) {
            return standard;
        }
    }

    static int seconds(Json arguments) {
        Integer timeout = ToolArguments.integer(arguments, "timeout");
        if (timeout == null) {
            return DEFAULT_TIMEOUT;
        }
        if (timeout < 1) {
            throw new IllegalArgumentException("timeout must be 1 or more seconds");
        }
        return Math.min(timeout, MAX_TIMEOUT);
    }

    @Override
    public LocalResult run(LocalContext context, Json arguments) throws Exception {
        String command = ToolArguments.string(arguments, "command");
        if (command == null || command.isBlank()) {
            return LocalResult.error("command is required");
        }
        int seconds;
        Path workdir;
        try {
            seconds = seconds(arguments);
            String dir = ToolArguments.string(arguments, "workdir");
            workdir = dir == null ? context.dirs().main() : context.dirs().resolve(dir);
            if (!Files.isDirectory(workdir)) {
                return LocalResult.error("Not a directory: " + dir);
            }
        } catch (IllegalArgumentException invalid) {
            return LocalResult.error(invalid.getMessage());
        }
        Shell.Run run = context.shell().run(command, workdir, Duration.ofSeconds(seconds));
        StringBuilder text = new StringBuilder(clean(run.output().text()));
        Tail.Text output = run.output();
        if (output.truncated() && output.cutLine()) {
            note(text, "[Output truncated: line " + output.totalLines() + " alone is longer than " + Tail.MAX_BYTES / 1024
                    + " KB; showing only its end (limit " + Tail.MAX_LINES + " lines or " + Tail.MAX_BYTES / 1024 + " KB).]");
        } else if (output.truncated()) {
            note(text, "[Output truncated: showing the last " + output.shownLines() + " of " + output.totalLines() + " lines (limit "
                    + Tail.MAX_LINES + " lines or " + Tail.MAX_BYTES / 1024 + " KB).]");
        }
        if (run.timedOut()) {
            note(text, "Command timed out after " + seconds + " s. It and everything it started were stopped.");
            return LocalResult.error(text.toString());
        }
        if (run.exitCode() != 0) {
            note(text, "Command exited with code " + run.exitCode() + ".");
            return LocalResult.error(text.toString());
        }
        return LocalResult.ok(text.isEmpty() ? "(no output)" : text.toString());
    }

    private static final java.util.regex.Pattern ANSI = java.util.regex.Pattern.compile(
            "\u001B\\[[0-?]*[ -/]*[@-~]|\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)?|\u001B[@-Z\\\\-_]");
    private static final java.util.regex.Pattern CONTROL = java.util.regex.Pattern.compile("[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F\uFFF9-\uFFFB]");

    /**
     * 交给模型前清理输出（照 pi {@code bash-executor.ts:79} 的 {@code sanitizeBinaryOutput(stripAnsi(text))}）：去掉 ANSI 颜色和光标控制、
     * 去掉制表符和换行以外的控制字符。单独的回车（进度条）照终端的样子只留最后一段，pi 是直接删掉回车、几段连在一起。
     */
    static String clean(String text) {
        String plain = CONTROL.matcher(ANSI.matcher(text.replace("\r\n", "\n")).replaceAll("")).replaceAll("");
        if (plain.indexOf('\r') < 0) {
            return plain;
        }
        StringBuilder out = new StringBuilder(plain.length());
        for (String line : plain.split("\n", -1)) {
            String shown = line;
            int cr = line.lastIndexOf('\r');
            while (cr >= 0) {
                String after = shown.substring(cr + 1);
                if (!after.isEmpty()) {
                    shown = after;
                    break;
                }
                shown = shown.substring(0, cr);
                cr = shown.lastIndexOf('\r');
            }
            out.append(shown).append('\n');
        }
        out.setLength(out.length() - 1);
        return out.toString();
    }

    private static void note(StringBuilder text, String line) {
        if (!text.isEmpty()) {
            text.append("\n\n");
        }
        text.append(line);
    }
}
