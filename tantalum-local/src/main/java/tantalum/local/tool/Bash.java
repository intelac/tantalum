package tantalum.local.tool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import tantalum.local.sandbox.Sandbox;
import tantalum.local.sandbox.Tail;
import tantalum.local.tool.ToolArguments.Param;
import tantalum.value.json.Json;

/**
 * 在用户电脑上的沙箱里跑一条 bash 命令。参数 {@code command}、{@code timeout}（秒，默认 120，最多 600，
 * 照 DSH {@code shell/bash-local/src/index.ts:102-106}，超过上限按上限算）、{@code workdir}（默认主目录，必须在允许的目录里）。
 * 标准输出和错误输出合在一起，只留最后 2000 行或 50 KB 并写明截断（照 pi {@code CA/src/core/tools/bash.ts:283}）；
 * 退出码不是 0 算错误结果，写明退出码。超时、取消、断开时沙箱结束整个进程树（{@link Sandbox#run}）。
 */
public final class Bash implements LocalTool {

    static final int DEFAULT_TIMEOUT = 120;
    static final int MAX_TIMEOUT = 600;
    /** 服务器多等一会儿，让沙箱自己的超时先到，超时的说明和输出能送回来。 */
    static final Duration REPLY_MARGIN = Duration.ofSeconds(15);

    @Override public String name() { return "bash"; }
    @Override public String description() {
        return "Run a bash command on the user's computer, inside a sandbox, in the main allowed directory or workdir. The command "
                + "can read the allowed directories and system tools, but not other files in the user's home. It can write only "
                + "inside the allowed directories, and only if the user allowed file changes; $TMPDIR is always writable and is "
                + "deleted afterwards. Network access may be off, and servers on the user's computer (localhost) are never "
                + "reachable. Returns stdout and stderr together; output is truncated to the last " + Tail.MAX_LINES
                + " lines or " + Tail.MAX_BYTES / 1024 + " KB. A non-zero exit code is an error. Prefer read_file, grep and "
                + "edit_file for reading and changing files.";
    }
    @Override public Kind kind() { return Kind.SHELL; }
    @Override public Json schema() {
        return ToolArguments.schema(List.of(
                Param.required("command", "string", "The bash command to run."),
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
        Sandbox.Run run = context.sandbox().run(command, workdir, Duration.ofSeconds(seconds));
        StringBuilder text = new StringBuilder(run.output().text());
        Tail.Text output = run.output();
        if (output.truncated()) {
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

    private static void note(StringBuilder text, String line) {
        if (!text.isEmpty()) {
            text.append("\n\n");
        }
        text.append(line);
    }
}
