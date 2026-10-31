package tantalum.shell.tool;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.MalformedInputException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.ToolOutput;
import tantalum.value.json.Json;

/** 在工作目录内按正则搜索。纯 Java 实现，不经过 shell，参数不会被当作命令。 */
public final class GrepTool implements Tool {

    private static final int MAX_MATCHES = 200;
    private static final long MAX_FILE_BYTES = 4 * 1024 * 1024;
    private static final int WORKSPACE_CONCURRENCY = 16;

    /** 让正则匹配能响应中断，否则超时后恶意正则会一直占住线程。 */
    private record InterruptibleText(CharSequence text) implements CharSequence {
        @Override
        public char charAt(int index) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("interrupted");
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

    @Override public String name() { return "grep"; }
    @Override public String description() {
        return "Search files with a Java regular expression. path is an optional directory or file relative to the workspace.";
    }
    @Override public Json schema() { return ToolArguments.schema("pattern", "path"); }
    @Override public String server() { return "workspace"; }
    @Override public int serverConcurrency() { return WORKSPACE_CONCURRENCY; }
    @Override public boolean isConcurrencySafe(Json arguments) { return true; }

    @Override
    public ToolOutput run(ToolContext context, Json arguments) {
        String regex = ToolArguments.string(arguments, "pattern");
        if (regex == null) {
            return ToolOutput.error("Missing argument: pattern");
        }
        String path = ToolArguments.string(arguments, "path");
        try {
            Pattern pattern = Pattern.compile(regex);
            Path start = context.workspace().resolve(path == null ? "." : path);
            List<String> matches = search(pattern, start, context.workspace().root());
            if (matches.isEmpty()) {
                return ToolOutput.ok("No matches.");
            }
            String text = String.join("\n", matches);
            return ToolOutput.ok(matches.size() >= MAX_MATCHES ? text + "\n(stopped at " + MAX_MATCHES + " matches)" : text);
        } catch (PatternSyntaxException failure) {
            return ToolOutput.error("Invalid pattern: " + failure.getDescription());
        } catch (IllegalArgumentException failure) {
            return ToolOutput.error(failure.getMessage());
        } catch (IOException | UncheckedIOException failure) {
            return ToolOutput.error("Search failed: " + failure.getMessage());
        }
    }

    private List<String> search(Pattern pattern, Path start, Path root) throws IOException {
        List<String> matches = new ArrayList<>();
        // Files.walk 默认不跟随符号链接，指向目录外的链接不会被进入。
        try (Stream<Path> files = Files.walk(start)) {
            for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                if (Files.isSymbolicLink(file) || Files.size(file) > MAX_FILE_BYTES) {
                    continue;
                }
                searchFile(pattern, file, root, matches);
                if (matches.size() >= MAX_MATCHES) {
                    break;
                }
            }
        }
        return matches;
    }

    private void searchFile(Pattern pattern, Path file, Path root, List<String> matches) throws IOException {
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (MalformedInputException binary) {
            return;
        }
        String relative = root.relativize(file).toString();
        for (int i = 0; i < lines.size() && matches.size() < MAX_MATCHES; i++) {
            if (pattern.matcher(new InterruptibleText(lines.get(i))).find()) {
                matches.add(relative + ":" + (i + 1) + ":" + lines.get(i));
            }
        }
    }
}
