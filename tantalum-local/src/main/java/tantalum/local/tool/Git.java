package tantalum.local.tool;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import tantalum.local.fs.AllowedDirs;
import tantalum.value.json.Json;

/**
 * 五个 Git 工具共用的执行（local-workspace L5、需求 3.2）：调本机的 {@code git}，参数是固定的 argv，不经过 shell；模型只能给路径、提交号、
 * 行范围、条数这些字段，以 {@code -} 开头的提交号一律拒绝，免得被当成选项。只读：
 * <ul>
 *   <li>比较改动只用不写索引的底层命令（见 {@link GitDiff}）；另设 {@code GIT_OPTIONAL_LOCKS=0}，别的命令也不顺手刷新索引；</li>
 *   <li>{@code --no-pager}、{@code GIT_TERMINAL_PROMPT=0}、标准输入接空：不等人输入；</li>
 *   <li>{@code core.fsmonitor=false}、{@code --no-ext-diff}、{@code --no-textconv}：不启动仓库配置里的外部程序；</li>
 *   <li>继承来的 {@code GIT_*} 变量去掉（比如 {@code GIT_DIR} 会把命令引到别的仓库），{@code TANTALUM_TOKEN} 也去掉。</li>
 * </ul>
 * 仓库的顶层目录必须在允许的目录里，否则 {@code git show} 能读到允许目录以外的文件；不在时和“不是仓库”报同一句话。
 * 输出最多 {@value #MAX_LINES} 行或 50 KB（同 read_file），超出就停止读取、结束 git，结尾写明截断。
 */
final class Git {

    static final int MAX_LINES = 2000;
    static final int MAX_BYTES = 50 * 1024;
    static final int MAX_LINE_CHARS = 2000;
    private static final int MAX_ERROR_CHARS = 1000;
    static final String NOT_A_REPOSITORY = "Not a Git repository inside the allowed directories: ";
    private static final List<String> BASE = List.of("git", "--no-pager", "-c", "core.fsmonitor=false", "-c", "core.quotePath=false");
    /** 比较、显示改动时都加：不启动外部 diff 程序和文本转换程序。 */
    static final List<String> NO_EXTERNAL = List.of("--no-color", "--no-ext-diff", "--no-textconv");

    private Git() {}

    /** git 跑完的结果。{@code truncated} 时 {@code text} 已带截断说明，退出码不再有意义。 */
    record Output(int exitCode, String text, boolean truncated, String errors) {

        boolean ok() {
            return truncated || exitCode == 0;
        }
    }

    /** 要在哪个仓库里跑：{@code dir} 是命令的工作目录，{@code target} 是模型给的路径（没给时为空）。 */
    record Place(Path dir, Path target) {}

    /**
     * 按 {@code path} 参数找仓库：没给时是主目录；给了文件时用它所在的目录。路径要在允许的目录里，所在仓库的顶层也要在。
     * 不满足时抛 {@link IllegalArgumentException}，越界和不存在的措辞同 {@link AllowedDirs}。
     */
    static Place place(LocalContext context, String path) throws IOException, InterruptedException {
        Path target = path == null ? null : context.dirs().resolve(path);
        Path dir = target == null ? context.dirs().main() : Files.isDirectory(target) ? target : target.getParent();
        Output top = run(dir, List.of("rev-parse", "--show-toplevel"));
        String shown = path == null ? "." : path;
        if (!top.ok() || top.text().isBlank()) {
            throw new IllegalArgumentException(NOT_A_REPOSITORY + shown);
        }
        try {
            context.dirs().resolve(top.text().strip());
        } catch (IllegalArgumentException outside) {
            throw new IllegalArgumentException(NOT_A_REPOSITORY + shown);
        }
        return new Place(dir, target);
    }

    /** 提交号、分支名这类参数：可以不给；给了就不能是空白、不能以 {@code -} 开头、不能有控制字符。 */
    static String revision(Json arguments, String name) {
        Json value = arguments instanceof Json.Obj object ? object.fields().get(name) : null;
        if (value == null || value instanceof Json.Null) {
            return null;
        }
        if (!(value instanceof Json.Str text) || text.value().isBlank()) {
            throw new IllegalArgumentException(name + " must be a non-empty string");
        }
        String revision = text.value().strip();
        if (revision.startsWith("-")) {
            throw new IllegalArgumentException(name + " must not start with -");
        }
        for (int i = 0; i < revision.length(); i++) {
            if (Character.isISOControl(revision.charAt(i))) {
                throw new IllegalArgumentException(name + " must not contain control characters");
            }
        }
        return revision;
    }

    /** 1 以上的整数参数；没给时返回 {@code fallback}，超过 {@code max} 时取 {@code max}。 */
    static int positive(Json arguments, String name, int fallback, int max) {
        Integer value = ToolArguments.integer(arguments, name);
        if (value == null) {
            return fallback;
        }
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be 1 or more");
        }
        return Math.min(value, max);
    }

    /** 在仓库里跑一个子命令，把结果变成给模型的文字；没有输出时用 {@code empty}。 */
    static LocalResult result(Place place, List<String> arguments, String empty) throws IOException, InterruptedException {
        Output output = run(place.dir(), arguments);
        if (!output.ok()) {
            String reason = output.errors().isBlank() ? "exit code " + output.exitCode() : output.errors().strip();
            return LocalResult.error("git " + arguments.getFirst() + " failed: " + reason);
        }
        return LocalResult.ok(output.text().isEmpty() ? empty : output.text());
    }

    /** 跑 git。线程被打断时结束 git 及其子进程，抛 {@link InterruptedException}。 */
    static Output run(Path dir, List<String> arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(BASE);
        command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command).directory(dir.toFile())
                .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        Map<String, String> environment = builder.environment();
        environment.keySet().removeIf(name -> name.startsWith("GIT_") || name.equals("TANTALUM_TOKEN"));
        environment.put("GIT_TERMINAL_PROMPT", "0");
        environment.put("GIT_OPTIONAL_LOCKS", "0");
        Process process = builder.start();
        try (ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                Future<Capped> out = readers.submit(() -> capped(process.getInputStream(), process));
                Future<String> err = readers.submit(() -> errors(process.getErrorStream()));
                int code = process.waitFor();
                Capped text = out.get();
                return new Output(code, text.text(), text.truncated(), err.get());
            } catch (ExecutionException failure) {
                throw new IOException("cannot read git output", failure.getCause());
            } finally {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        }
    }

    private record Capped(String text, boolean truncated) {}

    /** 逐字符读：每行最多 {@value #MAX_LINE_CHARS} 个字符；够了行数或字节数就停，结束 git，不再读剩下的。 */
    private static Capped capped(InputStream stream, Process process) throws IOException {
        StringBuilder text = new StringBuilder();
        StringBuilder line = new StringBuilder();
        int lines = 0;
        int bytes = 0;
        boolean cut = false;
        try (Reader in = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            for (int c = in.read(); ; c = in.read()) {
                if (c == '\n' || (c < 0 && !line.isEmpty())) {
                    String rendered = cut ? line + "... [line truncated to " + MAX_LINE_CHARS + " characters]" : line.toString();
                    int size = rendered.getBytes(StandardCharsets.UTF_8).length + 1;
                    if (lines == MAX_LINES || bytes + size > MAX_BYTES) {
                        process.destroyForcibly();
                        text.append("\n\n[Output truncated after ").append(lines).append(" lines (limit ").append(MAX_LINES)
                                .append(" lines or ").append(MAX_BYTES / 1024).append(" KB). Narrow it with path, revision or count.]");
                        return new Capped(text.toString(), true);
                    }
                    if (lines > 0) {
                        text.append('\n');
                    }
                    text.append(rendered);
                    lines++;
                    bytes += size;
                    line.setLength(0);
                    cut = false;
                }
                if (c < 0) {
                    return new Capped(text.toString(), false);
                }
                if (c == '\n') {
                    continue;
                }
                if (line.length() < MAX_LINE_CHARS) {
                    line.append((char) c);
                } else {
                    cut = true;
                }
            }
        }
    }

    /** 错误输出只留开头一段，够说明原因。 */
    private static String errors(InputStream stream) throws IOException {
        byte[] head = stream.readNBytes(MAX_ERROR_CHARS);
        // 剩下的读完丢掉，免得 git 因为错误输出的管道满了卡住
        stream.transferTo(OutputStream.nullOutputStream());
        return new String(head, StandardCharsets.UTF_8);
    }
}
