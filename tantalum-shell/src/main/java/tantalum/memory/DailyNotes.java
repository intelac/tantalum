package tantalum.memory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 每天的笔记（memory-capture）：每轮对话结束后自动记下的内容，放在每人记忆目录的 {@code daily/YYYY-MM-DD/<会话>.md}，
 * 一个会话一天一篇，每轮一段，段里有 {@code <!-- run:… -->} 标记。同一轮只记一次（看标记）；删掉的笔记留一个
 * {@code <会话>.deleted}，记着已经记过的轮次，重放和重试不会把它们写回来。写入先写临时文件再换过去。线程安全（按实例加锁）。
 */
public final class DailyNotes {

    public static final String DIR = "daily";
    private static final String SUFFIX = ".md";
    private static final String TOMBSTONE = ".deleted";
    private static final Pattern DAY = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern SESSION = Pattern.compile("[A-Za-z0-9_-]{1,100}");
    private static final Pattern MARKER = Pattern.compile("<!-- run:([A-Za-z0-9_-]{1,120}) -->");
    private static final Pattern SECTION = Pattern.compile("(?m)^## ");
    static final int PREVIEW_CHARS = 160;

    /** 一段：哪一轮、标题行、正文。 */
    public record Section(String run, String heading, String body) {}

    /** 一篇笔记的全文和分段。 */
    public record Note(String day, String session, String title, String text, List<Section> sections, Instant updated) {}

    /** 列表里的一篇。 */
    public record Listed(String session, String title, int sections, String preview, Instant updated) {}

    /** 一天的笔记，新的在前。 */
    public record Day(String day, List<Listed> notes) {}

    private final Path root;

    /** {@code memoryDir} 是这个人的记忆目录（{@link MemoryStore#dir()}）。 */
    public DailyNotes(Path memoryDir) {
        this.root = memoryDir.resolve(DIR);
    }

    public static boolean validDay(String day) {
        return day != null && DAY.matcher(day).matches();
    }

    public static boolean validSession(String session) {
        return session != null && SESSION.matcher(session).matches();
    }

    /** 这一轮记过没有（笔记里有标记，或者删掉时记在墓碑里）。 */
    public synchronized boolean noted(String day, String session, String run) {
        check(day, session);
        return runs(file(day, session)).contains(run) || tombstoned(day, session).contains(run);
    }

    /**
     * 在这一天这个会话的笔记后面加一段；文件不存在时连 frontmatter 一起建。这一轮记过就不写，返回 false。
     * {@code heading} 是标题行（不含 “## ”），{@code body} 是正文。
     */
    public synchronized boolean append(String day, String session, String title, String run, String heading, String body) {
        check(day, session);
        if (!MARKER.matcher("<!-- run:" + run + " -->").matches()) {
            throw new IllegalArgumentException("invalid run id: " + run);
        }
        if (noted(day, session, run)) {
            return false;
        }
        Path file = file(day, session);
        String existing = Files.exists(file) ? readString(file) : header(day, session, title);
        String section = "## " + oneLine(heading) + "\n<!-- run:" + run + " -->\n" + body.strip() + "\n";
        String text = existing.endsWith("\n\n") ? existing + section : existing.stripTrailing() + "\n\n" + section;
        writeAtomically(file, text);
        return true;
    }

    /** 最近 {@code days} 天有笔记的日子，新的在前；每天的笔记按更新时间新的在前。 */
    public synchronized List<Day> list(int days) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<Day> result = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(root)) {
            List<Path> sorted = dirs.filter(Files::isDirectory).filter(dir -> validDay(dir.getFileName().toString()))
                    .sorted(Comparator.comparing((Path dir) -> dir.getFileName().toString()).reversed()).toList();
            for (Path dir : sorted) {
                if (result.size() >= days) {
                    break;
                }
                List<Listed> notes = new ArrayList<>();
                try (Stream<Path> files = Files.list(dir)) {
                    for (Path file : (Iterable<Path>) files::iterator) {
                        String name = file.getFileName().toString();
                        if (!name.endsWith(SUFFIX) || !validSession(name.substring(0, name.length() - SUFFIX.length()))) {
                            continue;
                        }
                        Note note = parse(dir.getFileName().toString(), name.substring(0, name.length() - SUFFIX.length()), file);
                        String preview = note.sections().isEmpty() ? "" : note.sections().getLast().body();
                        notes.add(new Listed(note.session(), note.title(), note.sections().size(), cut(preview), note.updated()));
                    }
                }
                if (!notes.isEmpty()) {
                    notes.sort(Comparator.comparing(Listed::updated).reversed().thenComparing(Listed::session));
                    result.add(new Day(dir.getFileName().toString(), notes));
                }
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return result;
    }

    public synchronized Optional<Note> read(String day, String session) {
        check(day, session);
        Path file = file(day, session);
        return Files.isRegularFile(file) ? Optional.of(parse(day, session, file)) : Optional.empty();
    }

    /** 用户改过的全文整篇换掉。笔记不存在时返回 false。改掉的轮次标记照样记在墓碑里，不会因为重放写回来。 */
    public synchronized boolean write(String day, String session, String text) {
        check(day, session);
        Path file = file(day, session);
        if (!Files.isRegularFile(file)) {
            return false;
        }
        remember(day, session, runs(file));
        writeAtomically(file, text.endsWith("\n") ? text : text + "\n");
        return true;
    }

    /** 删掉一整篇；记过的轮次留在墓碑里。笔记不存在时返回 false。 */
    public synchronized boolean delete(String day, String session) {
        check(day, session);
        Path file = file(day, session);
        if (!Files.isRegularFile(file)) {
            return false;
        }
        remember(day, session, runs(file));
        try {
            Files.delete(file);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return true;
    }

    private void remember(String day, String session, Set<String> runs) {
        Set<String> all = new LinkedHashSet<>(tombstoned(day, session));
        all.addAll(runs);
        writeAtomically(tombstone(day, session), String.join("\n", all) + "\n");
    }

    private Set<String> tombstoned(String day, String session) {
        Path file = tombstone(day, session);
        Set<String> runs = new LinkedHashSet<>();
        if (Files.isRegularFile(file)) {
            readString(file).lines().map(String::strip).filter(line -> !line.isEmpty()).forEach(runs::add);
        }
        return runs;
    }

    private static Set<String> runs(Path file) {
        Set<String> runs = new LinkedHashSet<>();
        if (Files.isRegularFile(file)) {
            Matcher matcher = MARKER.matcher(readString(file));
            while (matcher.find()) {
                runs.add(matcher.group(1));
            }
        }
        return runs;
    }

    private static Note parse(String day, String session, Path file) {
        String text = readString(file);
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
        Instant updated;
        try {
            updated = Files.getLastModifiedTime(file).toInstant();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return new Note(day, session, title, text, sections, updated);
    }

    private static String header(String day, String session, String title) {
        return "---\nsession: " + session + "\ntitle: \"" + oneLine(title).replace("\"", "'") + "\"\nday: " + day + "\n---\n\n";
    }

    private static String unquote(String value) {
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"") ? value.substring(1, value.length() - 1) : value;
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").strip();
    }

    private static String cut(String text) {
        String flat = oneLine(text);
        return flat.length() <= PREVIEW_CHARS ? flat : flat.substring(0, PREVIEW_CHARS) + "…";
    }

    private void check(String day, String session) {
        if (!validDay(day)) {
            throw new IllegalArgumentException("invalid day: " + day);
        }
        if (!validSession(session)) {
            throw new IllegalArgumentException("invalid session: " + session);
        }
    }

    private Path file(String day, String session) {
        return root.resolve(day).resolve(session + SUFFIX);
    }

    private Path tombstone(String day, String session) {
        return root.resolve(day).resolve(session + TOMBSTONE);
    }

    private static String readString(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static void writeAtomically(Path file, String text) {
        try {
            Files.createDirectories(file.getParent());
            Path temp = Files.createTempFile(file.getParent(), ".note-", ".tmp");
            Files.writeString(temp, text, StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
