package tantalum.examples;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.WorkResult;
import tantalum.shell.plugin.WorkType;
import tantalum.shell.plugin.Workspace;
import tantalum.value.json.Json;

/**
 * 核对回答里形如 {@code FILE.cbl:42} 的引用：文件存在且行号有效。不通过时让模型修正。
 * 按字节数行，遗留 COBOL 源常见的非 UTF-8 编码也能核对。
 */
public final class CitationWork implements WorkType {

    public static final String TYPE = "citation";

    /** 行号最多 9 位，再长的不是行号。 */
    private static final Pattern CITATION = Pattern.compile("([\\w./-]+\\.\\w+):(\\d{1,9})(?!\\d)");

    @Override
    public String name() {
        return TYPE;
    }

    @Override
    public String plugin() {
        return "citationCheck";
    }

    @Override
    public WorkResult run(ToolContext context, Json input) throws IOException {
        String answer = input instanceof Json.Str text ? text.value() : "";
        List<String> wrong = new ArrayList<>();
        Matcher matcher = CITATION.matcher(answer);
        while (matcher.find()) {
            if (!valid(context.workspace(), matcher.group(1), Long.parseLong(matcher.group(2)))) {
                wrong.add(matcher.group());
            }
        }
        if (wrong.isEmpty()) {
            return new WorkResult(new Json.Str("ok"), null);
        }
        return new WorkResult(new Json.Str("wrong citations"),
                "These citations do not match the files: " + String.join(", ", wrong) + ". Please correct your answer.");
    }

    private static boolean valid(Workspace workspace, String file, long line) throws IOException {
        Path path;
        try {
            path = workspace.resolve(file);
        } catch (IllegalArgumentException outsideOrMissing) {
            return false;
        }
        if (!Files.isRegularFile(path)) {
            return false;
        }
        try {
            return line >= 1 && line <= countLines(path);
        } catch (UncheckedIOException unreadable) {
            return false;
        }
    }

    /** 行数 = 换行符数，末行没有换行时再加一。 */
    static long countLines(Path path) throws IOException {
        long newlines = 0;
        int last = -1;
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                for (int i = 0; i < read; i++) {
                    if (buffer[i] == '\n') {
                        newlines++;
                    }
                }
                last = buffer[read - 1];
            }
        }
        return last == -1 ? 0 : last == '\n' ? newlines : newlines + 1;
    }
}
