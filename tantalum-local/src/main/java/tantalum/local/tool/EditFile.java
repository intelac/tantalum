package tantalum.local.tool;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import tantalum.local.tool.ToolArguments.Param;
import tantalum.value.json.Json;

/**
 * 把文件里的一段原文换成新文字，参数同 DSH {@code tool-fs/src/edit.ts:80-90}。逐字匹配，不做模糊匹配；默认必须正好
 * 出现一次。文件和参数里的 CRLF 都换成 LF 再匹配，写回时按原来的换行还原、保留 BOM（pi {@code edit.ts:184-195}）。
 */
public final class EditFile implements LocalTool {


    @Override public String name() { return "edit_file"; }
    @Override public String description() {
        return "Replace an exact piece of text in a file in the allowed directories on the user's computer. old_string must match "
                + "the file exactly, including whitespace and line breaks, and must occur exactly once unless replace_all is true; "
                + "include enough surrounding lines to make it unique. "
                + "You must have read the file with read_file in this session.";
    }
    @Override public Kind kind() { return Kind.WRITE; }
    @Override public Json schema() {
        return ToolArguments.schema(List.of(
                Param.required("path", "string", "File path, relative to the main directory or absolute."),
                Param.required("old_string", "string", "The exact text to replace, copied from the file without any line numbers."),
                Param.required("new_string", "string", "The text to put in its place."),
                Param.optional("replace_all", "boolean", "Replace every occurrence instead of exactly one. Default false.")));
    }

    @Override
    public LocalResult run(LocalContext context, Json arguments) {
        String path = ToolArguments.string(arguments, "path");
        String oldString = ToolArguments.string(arguments, "old_string");
        String newString = ToolArguments.string(arguments, "new_string");
        if (path == null || oldString == null || newString == null) {
            return LocalResult.error("Missing argument: " + (path == null ? "path" : oldString == null ? "old_string" : "new_string"));
        }
        try {
            boolean replaceAll = ToolArguments.bool(arguments, "replace_all", false);
            String from = TextFiles.toLf(oldString);
            String to = TextFiles.toLf(newString);
            if (from.isEmpty()) {
                return LocalResult.error("cannot edit \"" + path + "\": old_string is empty — to create a file, use write_file");
            }
            if (from.equals(to)) {
                return LocalResult.error("cannot edit \"" + path + "\": old_string and new_string are the same, nothing would change");
            }
            context.dirs().resolve(path);
            Path target = WritePaths.resolve(context.dirs(), path);
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                return LocalResult.error("Not a file: " + path);
            }
            return context.observations().locked(target, () -> edit(context, path, target, from, to, replaceAll));
        } catch (IllegalArgumentException failure) {
            return LocalResult.error(failure.getMessage());
        } catch (IOException failure) {
            return LocalResult.error("cannot edit \"" + path + "\": " + failure.getClass().getSimpleName());
        }
    }

    /** 持有文件锁时执行：检查读过、读全文、替换、写回、记下新版本。 */
    private LocalResult edit(LocalContext context, String path, Path target, String from, String to, boolean replaceAll) throws IOException {
        Observations observations = context.observations();
        String refusal = WritePaths.refusal(observations.check(context.session(), target), path);
        if (refusal != null) {
            return LocalResult.error(refusal);
        }
        if (Files.size(target) > WriteFile.MAX_BYTES) {
            return LocalResult.error("cannot edit \"" + path + "\": file is larger than " + WriteFile.MAX_BYTES / (1024 * 1024) + " MB");
        }
        if (TextFiles.isBinary(target)) {
            return LocalResult.error("cannot edit \"" + path + "\": binary file");
        }
        TextFiles.Text text;
        try {
            text = TextFiles.read(target);
        } catch (CharacterCodingException notUtf8) {
            return LocalResult.error("cannot edit \"" + path + "\": not a UTF-8 text file");
        }
        int count = occurrences(text.content(), from);
        if (count == 0) {
            return LocalResult.error("cannot edit \"" + path + "\": old_string was not found. It must match the file exactly, including "
                    + "whitespace and line breaks; read the file again if unsure.");
        }
        if (count > 1 && !replaceAll) {
            return LocalResult.error("cannot edit \"" + path + "\": old_string occurs " + count + " times. Include more surrounding lines "
                    + "to make it unique, or set replace_all to true.");
        }
        String content = text.content();
        String edited = replaceAll ? content.replace(from, to)
                : content.substring(0, content.indexOf(from)) + to + content.substring(content.indexOf(from) + from.length());
        byte[] bytes = text.encode(edited);
        if (bytes.length > WriteFile.MAX_BYTES) {
            return LocalResult.error("cannot edit \"" + path + "\": the result would be larger than "
                    + WriteFile.MAX_BYTES / (1024 * 1024) + " MB");
        }
        TextFiles.writeAtomically(target, bytes);
        observations.record(context.session(), target);
        return LocalResult.ok("Edited " + path + ": replaced " + count + (count == 1 ? " occurrence." : " occurrences."));
    }

    /** 不重叠的出现次数，和 {@link String#replace} 的替换次数一致。 */
    private static int occurrences(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) {
            count++;
        }
        return count;
    }
}
