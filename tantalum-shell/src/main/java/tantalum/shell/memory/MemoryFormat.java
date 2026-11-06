package tantalum.shell.memory;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tantalum.shell.memory.MemoryStore.Problem;
import tantalum.shell.memory.MemoryStore.WriteResult.Rejected;
import tantalum.value.id.ReservedName;

/**
 * 记忆文件格式的纯函数：条目文件（frontmatter + Markdown 正文）和索引行。
 * frontmatter 只有四个固定键，手写解析，不引入 YAML 库。
 */
public final class MemoryFormat {

    public static final Pattern NAME = Pattern.compile("[a-z0-9-]{1,64}");
    public static final int MAX_DESCRIPTION_CHARS = 200;
    static final String INDEX_FILE = "MEMORY.md";

    private static final String FENCE = "---";
    private static final Pattern INDEX_LINE = Pattern.compile("^- \\[([a-z0-9-]{1,64})]\\(\\1\\.md\\) — ([a-z]+) — (.*)$");

    /** 索引里的一条：名字、类型、描述。 */

    private MemoryFormat() {}

    // ---- 校验 ----

    /** 名称、类型、描述、正文的检查；返回空表示通过，否则是拒绝：英文原因给模型和日志，种类和参数给控制台（console-i18n I9）。 */
    public static Optional<Rejected> validate(String name, String type, String description, String content, int maxEntryBytes) {
        if (name != null && NAME.matcher(name).matches() && ReservedName.matches(name)) {
            return rejected(Problem.RESERVED_NAME, name + " is a reserved device name on Windows; choose another name", Map.of());
        }
        if (!isValidName(name)) {
            return rejected(Problem.INVALID_NAME, "name must match [a-z0-9-]{1,64}", Map.of());
        }
        if (MemoryType.parse(type).isEmpty()) {
            return rejected(Problem.INVALID, "type must be one of " + MemoryType.keys(), Map.of("field", "type"));
        }
        if (description == null || description.isBlank()) {
            return rejected(Problem.INVALID, "description must not be empty", Map.of("field", "description"));
        }
        if (description.length() > MAX_DESCRIPTION_CHARS || description.contains("\n") || description.contains("\r")) {
            return rejected(Problem.INVALID, "description must be a single line of at most " + MAX_DESCRIPTION_CHARS + " characters",
                    Map.of("field", "description"));
        }
        if (content == null) {
            return rejected(Problem.INVALID, "content must not be null", Map.of("field", "content"));
        }
        int bytes = content.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > maxEntryBytes) {
            return rejected(Problem.TOO_LONG, "content is " + bytes + " bytes; the limit is " + maxEntryBytes, Map.of("limit", maxEntryBytes));
        }
        return Optional.empty();
    }

    private static Optional<Rejected> rejected(Problem problem, String reason, Map<String, Object> params) {
        return Optional.of(new Rejected(problem, reason, params));
    }

    /** 记忆名加 {@code .md} 就是文件名，所以 Windows 的设备名（{@code con}、{@code nul}，{@link ReservedName}）也不行。 */
    public static boolean isValidName(String name) {
        return name != null && NAME.matcher(name).matches() && !ReservedName.matches(name);
    }

    // ---- 条目文件 ----

    public static String formatEntry(MemoryEntry entry) {
        return FENCE + "\n"
                + "name: " + entry.name() + "\n"
                + "description: " + entry.description() + "\n"
                + "type: " + entry.type().key() + "\n"
                + "updated: " + entry.updatedAt() + "\n"
                + FENCE + "\n\n"
                + entry.content();
    }

    /** 解析失败（缺围栏、缺键、类型不认识、时间格式不对）返回空。 */
    public static Optional<MemoryEntry> parseEntry(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String normalized = text.replace("\r\n", "\n");
        if (!normalized.startsWith(FENCE + "\n")) {
            return Optional.empty();
        }
        int end = normalized.indexOf("\n" + FENCE + "\n", FENCE.length());
        if (end < 0) {
            return Optional.empty();
        }
        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : normalized.substring(FENCE.length() + 1, end).split("\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                return Optional.empty();
            }
            fields.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
        }
        String name = fields.get("name");
        Optional<MemoryType> type = MemoryType.parse(fields.get("type"));
        String description = fields.get("description");
        String updated = fields.get("updated");
        if (!isValidName(name) || type.isEmpty() || description == null || updated == null) {
            return Optional.empty();
        }
        Instant at;
        try {
            at = Instant.parse(updated);
        } catch (DateTimeParseException bad) {
            return Optional.empty();
        }
        String body = normalized.substring(end + FENCE.length() + 2);
        if (body.startsWith("\n")) {
            body = body.substring(1);
        }
        return Optional.of(new MemoryEntry(name, type.get(), description, body, at));
    }

    // ---- 索引 ----

    public static String formatIndexLine(MemoryEntry entry) {
        return "- [" + entry.name() + "](" + entry.name() + ".md) — " + entry.type().key() + " — " + entry.description();
    }

    /** 整份索引由条目生成，每条一行，按名字排序。 */
    public static String formatIndex(List<MemoryEntry> entries) {
        StringBuilder text = new StringBuilder();
        entries.stream().sorted((a, b) -> a.name().compareTo(b.name()))
                .forEach(entry -> text.append(formatIndexLine(entry)).append('\n'));
        return text.toString();
    }

    /** 索引里格式正确、类型也认识的一行。坏行在读索引时跳过。 */
    public static boolean isIndexLine(String line) {
        Matcher matcher = INDEX_LINE.matcher(line.strip());
        return matcher.matches() && MemoryType.parse(matcher.group(2)).isPresent();
    }

    public static int utf8Length(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }
}
