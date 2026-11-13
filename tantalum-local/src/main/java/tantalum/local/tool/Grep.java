package tantalum.local.tool;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.MalformedInputException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;
import tantalum.local.fs.AllowedDirs;
import tantalum.value.json.Json;

/**
 * 在用户电脑上允许的目录里按正则搜索。纯 Java 实现，不经过 shell，参数不会被当作命令。
 * 跳过的和 pi、DSH 的搜索（都是 ripgrep 的默认规则）一样：在 Git 仓库里只搜 Git 不忽略的文件（{@code .gitignore}、
 * {@code .git/info/exclude}、全局忽略规则，用 {@code git ls-files} 算）；不在仓库里或没装 git 时跳过 {@code .git}、
 * {@code node_modules} 这几个目录（同 {@code find_files}）；开头带 NUL 的二进制文件不搜（agent-eval 工具边界测试发现的）。
 * 隐藏文件照搜，同 pi。
 */
public final class Grep implements LocalTool {

    static final int MAX_MATCHES = 200;
    static final int MAX_LINE_CHARS = 500;
    static final int MAX_OUTPUT_CHARS = 64 * 1024;
    private static final long MAX_FILE_BYTES = 4 * 1024 * 1024;

    /** 让正则匹配能响应中断，否则超时后恶意正则会一直占住线程。 */
    private record InterruptibleText(CharSequence text) implements CharSequence {
        @Override
        public char charAt(int index) {
            if (Thread.currentThread().isInterrupted()) {
                throw new SearchInterrupted();
            }
            return text.charAt(index);
        }

        @Override public int length() { return text.length(); }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new InterruptibleText(text.subSequence(start, end));
        }

        @Override public String toString() { return text.toString(); }
    }

    private static final class SearchInterrupted extends RuntimeException {
        SearchInterrupted() {
            super("interrupted", null, false, false);
        }
    }

    @Override public String name() { return "grep"; }
    @Override public String description() {
        return "Search files in the allowed directories on the user's computer with a Java regular expression. path is an optional "
                + "directory or file, relative to the main directory or absolute. Default: the main directory. glob limits the search "
                + "to matching files, like find_files: *.py matches file names at any depth, src/**/*.ts matches paths under path. "
                + "In a Git repository files ignored by Git are skipped; binary files are skipped.";
    }
    @Override public Kind kind() { return Kind.READ; }
    /** {@code glob} 照 pi 的 grep（{@code glob}）和 DSH 的 grep（{@code include}）：只搜文件名或路径对得上的文件。 */
    @Override public Json schema() {
        return ToolArguments.schema(List.of(
                ToolArguments.Param.required("pattern", "string", "Java regular expression."),
                ToolArguments.Param.optional("path", "string", "Directory or file to search, relative to the main directory or absolute. Default: the main directory."),
                ToolArguments.Param.optional("glob", "string", "Only search files matching this glob, e.g. *.py or src/**/*.ts.")));
    }
    @Override public boolean isConcurrencySafe(Json arguments) { return true; }

    @Override
    public LocalResult run(LocalContext context, Json arguments) {
        String regex = ToolArguments.string(arguments, "pattern");
        if (regex == null) {
            return LocalResult.error("Missing argument: pattern");
        }
        String path = ToolArguments.string(arguments, "path");
        try {
            Pattern pattern = Pattern.compile(regex);
            Path start = context.dirs().resolve(path == null ? "." : path);
            String glob = ToolArguments.string(arguments, "glob");
            java.util.function.Predicate<Path> only = glob == null || glob.isBlank() ? file -> true
                    : FindFiles.glob(glob, Files.isDirectory(start) ? start : start.getParent());
            List<String> matches = search(pattern, start, context.dirs(), only);
            if (matches.isEmpty()) {
                return LocalResult.ok("No matches.");
            }
            return LocalResult.ok(render(matches));
        } catch (PatternSyntaxException failure) {
            return LocalResult.error("Invalid pattern: " + failure.getDescription());
        } catch (IllegalArgumentException failure) {
            return LocalResult.error(failure.getMessage());
        } catch (SearchInterrupted interrupted) {
            Thread.currentThread().interrupt();
            return LocalResult.error("Search interrupted.");
        } catch (IOException | UncheckedIOException failure) {
            return LocalResult.error("Search failed: " + failure.getClass().getSimpleName());
        }
    }

    /** 每行和总量都有上限，否则一个压缩过的单行文件就能撑爆模型上下文。 */
    private static String render(List<String> matches) {
        StringBuilder text = new StringBuilder();
        for (String match : matches) {
            if (text.length() + match.length() > MAX_OUTPUT_CHARS) {
                text.append("(output truncated at ").append(MAX_OUTPUT_CHARS).append(" characters)");
                return text.toString();
            }
            text.append(match).append('\n');
        }
        if (matches.size() >= MAX_MATCHES) {
            text.append("(stopped at ").append(MAX_MATCHES).append(" matches)");
        } else {
            text.setLength(text.length() - 1);
        }
        return text.toString();
    }

    /** 单个文件读不了（权限、遍历途中被删）就跳过它，已找到的匹配不丢。 */
    private List<String> search(Pattern pattern, Path start, AllowedDirs dirs, java.util.function.Predicate<Path> only) throws IOException {
        List<String> matches = new ArrayList<>();
        List<Path> tracked = Files.isDirectory(start, java.nio.file.LinkOption.NOFOLLOW_LINKS) ? gitFiles(start) : null;
        // Files.walk 默认不跟随符号链接，指向目录外的链接不会被进入。
        try (Stream<Path> files = tracked != null ? tracked.stream() : Files.walk(start).filter(file -> !skipped(start, file))) {
            for (Path file : (Iterable<Path>) files::iterator) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new SearchInterrupted();
                }
                try {
                    if (!Files.isRegularFile(file) || Files.isSymbolicLink(file) || Files.size(file) > MAX_FILE_BYTES || !only.test(file)) {
                        continue;
                    }
                    searchFile(pattern, file, dirs, matches);
                } catch (IOException | UncheckedIOException unreadable) {
                    continue;
                }
                if (matches.size() >= MAX_MATCHES) {
                    break;
                }
            }
        }
        return matches;
    }

    /** 不在仓库里时：路径上有 {@code .git}、{@code node_modules} 这类目录的跳过。 */
    private static boolean skipped(Path start, Path file) {
        for (Path part : start.relativize(file)) {
            if (FindFiles.SKIPPED.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code start} 在 Git 仓库里时，它下面 Git 不忽略的文件（已跟踪的加上没跟踪但没被忽略的），按路径排序；
     * 不在仓库里、没装 git、git 出错或超过 10 秒时返回 null，改为遍历目录。标准输入直接关掉，不用 {@code /dev/null}（Windows 没有）。
     */
    static List<Path> gitFiles(Path start) {
        Process process;
        try {
            process = new ProcessBuilder("git", "-c", "core.quotePath=false", "-c", "core.fsmonitor=false",
                    "ls-files", "-z", "--cached", "--others", "--exclude-standard")
                    .directory(start.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException noGit) {
            return null;
        }
        try {
            process.getOutputStream().close();
            byte[] output;
            try (InputStream in = process.getInputStream()) {
                output = readCapped(in);
            }
            if (output == null || !process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) {
                return null;
            }
            List<Path> files = new ArrayList<>();
            for (String name : new String(output, java.nio.charset.StandardCharsets.UTF_8).split("\0")) {
                if (!name.isEmpty()) {
                    files.add(start.resolve(name));
                }
            }
            return files.stream().distinct().sorted().toList();
        } catch (IOException | InterruptedException | java.nio.file.InvalidPathException failed) {
            if (failed instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        } finally {
            process.destroy();
        }
    }

    /** 文件清单最多 64 MB；再多当作 git 不可用。 */
    private static byte[] readCapped(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        for (int read; (read = in.read(buffer)) >= 0; ) {
            out.write(buffer, 0, read);
            if (out.size() > 64 * 1024 * 1024) {
                return null;
            }
        }
        return out.toByteArray();
    }

    private void searchFile(Pattern pattern, Path file, AllowedDirs dirs, List<String> matches) throws IOException {
        if (TextFiles.isBinary(file)) {
            return;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (MalformedInputException binary) {
            return;
        }
        String name = dirs.display(file);
        for (int i = 0; i < lines.size() && matches.size() < MAX_MATCHES; i++) {
            String line = lines.get(i);
            if (pattern.matcher(new InterruptibleText(line)).find()) {
                String shown = line.length() > MAX_LINE_CHARS ? line.substring(0, MAX_LINE_CHARS) + "..." : line;
                matches.add(name + ":" + (i + 1) + ":" + shown);
            }
        }
    }
}
