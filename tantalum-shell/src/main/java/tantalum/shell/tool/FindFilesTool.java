package tantalum.shell.tool;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.PatternSyntaxException;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.ToolOutput;
import tantalum.shell.tool.ToolArguments.Param;
import tantalum.value.json.Json;

/**
 * 按 glob 找文件（需求 4、C10）。不带 {@code /} 的模式匹配任意深度的文件名，带 {@code /} 时按相对 {@code path} 的路径匹配（DSH
 * {@code glob.ts:323-325}）。跳过版本库目录和 {@code node_modules}，不读 {@code .gitignore}，不跟随符号链接，只返回普通文件。
 * 结果是相对工作目录的路径，按路径排序，可以直接交给 {@code read_file}。
 */
public final class FindFilesTool implements Tool {

    static final int MAX_RESULTS = 1000;
    static final Set<String> SKIPPED = Set.of(".git", ".svn", ".hg", "node_modules");
    private static final int WORKSPACE_CONCURRENCY = 16;

    @Override public String name() { return "find_files"; }
    @Override public String description() {
        return "Find files by glob pattern. A pattern without / matches file names at any depth (*.java); a pattern with / matches "
                + "paths relative to path (src/**/*Test.java). Skips .git, .svn, .hg and node_modules. Returns paths relative to the "
                + "workspace, sorted.";
    }
    @Override public Json schema() {
        return ToolArguments.schema(List.of(
                Param.required("pattern", "string", "Glob pattern, e.g. *.md or src/**/*.java."),
                Param.optional("path", "string", "Directory to search, relative to the workspace. Default: the workspace root.")));
    }
    @Override public String server() { return "workspace"; }
    @Override public int serverConcurrency() { return WORKSPACE_CONCURRENCY; }
    @Override public boolean isConcurrencySafe(Json arguments) { return true; }

    @Override
    public ToolOutput run(ToolContext context, Json arguments) {
        String pattern = ToolArguments.string(arguments, "pattern");
        if (pattern == null || pattern.isBlank()) {
            return ToolOutput.error("Missing argument: pattern");
        }
        String path = ToolArguments.string(arguments, "path");
        String shown = path == null ? "." : path;
        try {
            List<PathMatcher> matchers = matchers(pattern);
            Path root = context.workspace().root();
            Path start = context.workspace().resolve(shown);
            if (!Files.isDirectory(start, LinkOption.NOFOLLOW_LINKS)) {
                return ToolOutput.error("Not a directory: " + shown);
            }
            boolean byName = !pattern.contains("/");
            List<String> found = new ArrayList<>();
            Files.walkFileTree(start, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                    if (Thread.currentThread().isInterrupted()) {
                        return FileVisitResult.TERMINATE;
                    }
                    return !dir.equals(start) && SKIPPED.contains(dir.getFileName().toString())
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    Path candidate = byName ? file.getFileName() : start.relativize(file);
                    if (attributes.isRegularFile() && matchers.stream().anyMatch(matcher -> matcher.matches(candidate))) {
                        found.add(root.relativize(file).toString());
                    }
                    return FileVisitResult.CONTINUE;
                }

                /** 读不了的目录跳过，不让一个没有权限的子目录挡住整个搜索。 */
                @Override
                public FileVisitResult visitFileFailed(Path file, IOException failure) {
                    return FileVisitResult.CONTINUE;
                }
            });
            if (Thread.currentThread().isInterrupted()) {
                return ToolOutput.error("find_files was interrupted");
            }
            if (found.isEmpty()) {
                return ToolOutput.ok("No files found.");
            }
            found.sort(null);
            StringBuilder out = new StringBuilder(String.join("\n", found.subList(0, Math.min(found.size(), MAX_RESULTS))));
            if (found.size() > MAX_RESULTS) {
                out.append("\n\n[Showing ").append(MAX_RESULTS).append(" of ").append(found.size())
                        .append(" files. Narrow the pattern or the path.]");
            }
            return ToolOutput.ok(out.toString());
        } catch (PatternSyntaxException failure) {
            return ToolOutput.error("Invalid pattern: " + failure.getDescription());
        } catch (IllegalArgumentException failure) {
            return ToolOutput.error(failure.getMessage());
        } catch (IOException failure) {
            return ToolOutput.error("Cannot search " + shown + ": " + failure.getClass().getSimpleName());
        }
    }

    /**
     * Java 的 {@code **}{@code /} 至少要有一层目录；常见的写法 {@code src/**}{@code /*.java} 也该匹配 {@code src/A.java}，
     * 所以再加一个去掉这一层的模式。
     */
    private static List<PathMatcher> matchers(String pattern) {
        List<String> patterns = new ArrayList<>(List.of(pattern));
        if (pattern.startsWith("**/")) {
            patterns.add(pattern.substring(3));
        }
        if (pattern.contains("/**/")) {
            patterns.add(pattern.replace("/**/", "/"));
        }
        return patterns.stream().map(glob -> FileSystems.getDefault().getPathMatcher("glob:" + glob)).toList();
    }
}
