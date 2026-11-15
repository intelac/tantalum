package tantalum.memory.ledger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 旧格式的每天笔记（memory-capture，2026-11-15）的读取，只给迁移用：{@code daily/YYYY-MM-DD/<会话>.md}，每轮一段，
 * 段里有 {@code <!-- run:… -->} 标记；删掉的笔记留 {@code <会话>.deleted}，里面是记过的轮次。也给新的 {@code DailyNotes}
 * 解析用户改过的全文用（格式不变）。
 */
public final class LegacyNotes {

    public static final String DIR = "daily";
    static final Pattern DAY = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    static final Pattern SESSION = Pattern.compile("[A-Za-z0-9_-]{1,100}");
    public static final Pattern MARKER = Pattern.compile("<!-- run:([A-Za-z0-9_-]{1,120}) -->");
    private static final Pattern SECTION = Pattern.compile("(?m)^## ");

    /** 一段：哪一轮（没有为 null）、标题行、正文。 */
    public record Section(String run, String heading, String body) {}

    /** 解析出来的一篇：标题和分段。 */
    public record Parsed(String title, List<Section> sections) {}

    /** 一篇旧文件：哪天、哪个会话、内容、改动时间。 */
    record File(String day, String session, Parsed parsed, Instant modified) {}

    private LegacyNotes() {}

    public static boolean validDay(String day) {
        return day != null && DAY.matcher(day).matches();
    }

    public static boolean validSession(String session) {
        return session != null && SESSION.matcher(session).matches();
    }

    /** 全文 → 标题和分段（frontmatter 里的 title；每个 “## ” 开头一段）。 */
    public static Parsed parse(String text) {
        String title = "";
        String body = text;
        if (text.startsWith("---\n")) {
            int end = text.indexOf("\n---\n", 4);
            if (end > 0) {
                for (String line : text.substring(4, end).split("\n")) {
                    if (line.startsWith("title:")) {
                        title = unquote(line.substring("title:".length()).strip());
                    }
                }
                body = text.substring(end + 5);
            }
        }
        List<Section> sections = new ArrayList<>();
        String[] parts = SECTION.split(body);
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];
            int newline = part.indexOf('\n');
            String heading = newline < 0 ? part.strip() : part.substring(0, newline).strip();
            String rest = newline < 0 ? "" : part.substring(newline + 1);
            Matcher marker = MARKER.matcher(rest);
            String run = marker.find() ? marker.group(1) : null;
            sections.add(new Section(run, heading, MARKER.matcher(rest).replaceAll("").strip()));
        }
        return new Parsed(title, sections);
    }

    /** 一篇的全文，和旧格式逐字相同：frontmatter 加每段 “## 标题 / 标记 / 正文”。 */
    public static String render(String day, String session, String title, List<Section> sections) {
        StringBuilder text = new StringBuilder("---\nsession: " + session + "\ntitle: \"" + oneLine(title).replace("\"", "'") + "\"\nday: "
                + day + "\n---\n\n");
        for (int i = 0; i < sections.size(); i++) {
            Section section = sections.get(i);
            if (i > 0) {
                text.append('\n');
            }
            text.append("## ").append(oneLine(section.heading())).append('\n');
            if (section.run() != null) {
                text.append("<!-- run:").append(section.run()).append(" -->\n");
            }
            text.append(section.body().strip()).append('\n');
        }
        return text.toString();
    }

    /** 旧目录下的全部笔记文件。 */
    static List<File> files(Path memoryDir) {
        Path root = memoryDir.resolve(DIR);
        List<File> files = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return files;
        }
        try (var days = Files.list(root)) {
            for (Path dayDir : days.filter(Files::isDirectory).sorted().toList()) {
                String day = dayDir.getFileName().toString();
                if (!validDay(day)) {
                    continue;
                }
                try (var notes = Files.list(dayDir)) {
                    for (Path file : notes.sorted().toList()) {
                        String name = file.getFileName().toString();
                        if (!name.endsWith(".md") || !validSession(name.substring(0, name.length() - 3))) {
                            continue;
                        }
                        files.add(new File(day, name.substring(0, name.length() - 3), parse(read(file)),
                                Files.getLastModifiedTime(file).toInstant()));
                    }
                }
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return files;
    }

    /** 旧目录下被删掉的笔记记过的轮次：(日期, 会话) → 轮次。 */
    static List<String[]> tombstoned(Path memoryDir) {
        Path root = memoryDir.resolve(DIR);
        List<String[]> runs = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return runs;
        }
        try (var days = Files.list(root)) {
            for (Path dayDir : days.filter(Files::isDirectory).sorted().toList()) {
                String day = dayDir.getFileName().toString();
                if (!validDay(day)) {
                    continue;
                }
                try (var notes = Files.list(dayDir)) {
                    for (Path file : notes.sorted().toList()) {
                        String name = file.getFileName().toString();
                        if (!name.endsWith(".deleted")) {
                            continue;
                        }
                        String session = name.substring(0, name.length() - ".deleted".length());
                        Set<String> seen = new LinkedHashSet<>();
                        read(file).lines().map(String::strip).filter(line -> !line.isEmpty()).forEach(seen::add);
                        seen.forEach(run -> runs.add(new String[] {day, session, run}));
                    }
                }
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return runs;
    }

    static String oneLine(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").strip();
    }

    private static String unquote(String value) {
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"") ? value.substring(1, value.length() - 1) : value;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
