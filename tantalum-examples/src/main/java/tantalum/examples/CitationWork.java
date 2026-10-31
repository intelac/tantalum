package tantalum.examples;

import java.io.IOException;
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

/** 核对回答里形如 {@code FILE.cbl:42} 的引用：文件存在且行号有效。不通过时让模型修正。 */
public final class CitationWork implements WorkType {

    public static final String TYPE = "citation";

    private static final Pattern CITATION = Pattern.compile("([\\w./-]+\\.\\w+):(\\d+)");

    @Override
    public String name() {
        return TYPE;
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
        try (var lines = Files.lines(path)) {
            return line >= 1 && line <= lines.count();
        }
    }
}
