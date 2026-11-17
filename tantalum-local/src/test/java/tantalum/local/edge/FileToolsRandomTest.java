package tantalum.local.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tantalum.local.fs.AllowedDirs;
import tantalum.local.tool.EditFile;
import tantalum.local.tool.FindFiles;
import tantalum.local.tool.Grep;
import tantalum.local.tool.ListDir;
import tantalum.local.tool.LocalContext;
import tantalum.local.tool.LocalResult;
import tantalum.local.tool.Observations;
import tantalum.local.tool.ReadFile;
import tantalum.local.tool.WriteFile;
import tantalum.value.json.Json;
import tantalum.local.tool.LocalContextFixture;

/**
 * 文件工具的随机序列（agent-eval 需求 1.6，照 pi {@code packages/env/test/differential.test.ts} 带种子的做法）：对一个临时目录随机做
 * 写、改、读、列、按名找、搜、外部改动，每一步同时在一份“应该是什么样”的副本（路径 → 文字）上算一遍，工具的结果和磁盘上的内容都要和它一致。
 * 失败消息带种子、步数和那一步做了什么，用 {@code -Dedge.seeds=种子} 单独重跑；{@code -Dedge.count=N} 跑更多种子。
 * 只用 LF 和小写名字：CRLF、BOM、大小写排序各有专门的测试。
 */
class FileToolsRandomTest {

    private static final int STEPS = 30;
    private static final List<String> DIRS = List.of("", "a/", "b/c/");
    private static final List<String> NAMES = List.of("f1.txt", "f2.txt", "notes.md", "g.txt");
    private static final List<String> WORDS = List.of("alpha", "beta", "gamma", "价格", "名字", "x1", "tab\there", "needle", "ab", "a b");

    @TempDir
    Path temp;

    static Stream<Long> seeds() {
        String given = System.getProperty("edge.seeds", "");
        if (!given.isBlank()) {
            return Stream.of(given.split(",")).map(String::strip).map(Long::parseLong);
        }
        int count = Integer.getInteger("edge.count", 40);
        return Stream.iterate(1L, seed -> seed + 1).limit(count);
    }

    private Path root;
    private LocalContext context;
    /** 应该是什么样：相对路径 → 文字。 */
    private final Map<String, String> model = new TreeMap<>();
    /** 这次会话里读过或自己写过、之后没被外部改过的文件（改和覆盖要这个）。 */
    private final Set<String> fresh = new HashSet<>();
    private final List<String> log = new ArrayList<>();

    private static Json args(String... pairs) {
        Map<String, Json> fields = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            fields.put(pairs[i], new Json.Str(pairs[i + 1]));
        }
        return new Json.Obj(fields);
    }

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    void toolsAgreeWithAPlainModelOfTheFiles(long seed) throws Exception {
        root = Files.createDirectory(temp.resolve("root")).toRealPath();
        context = LocalContextFixture.of("one", new AllowedDirs(List.of(root)), new Observations());
        Random random = new Random(seed);
        for (int step = 1; step <= STEPS; step++) {
            try {
                log.add(step + ". " + step(random));
                checkDisk();
            } catch (AssertionError wrong) {
                fail("seed=" + seed + " step=" + step + "\n" + wrong.getMessage() + "\nsteps so far:\n" + String.join("\n", log));
            }
        }
    }

    private String step(Random random) throws IOException {
        int op = random.nextInt(100);
        String path = DIRS.get(random.nextInt(DIRS.size())) + NAMES.get(random.nextInt(NAMES.size()));
        if (op < 25 || model.isEmpty()) {
            String content = text(random);
            LocalResult result = new WriteFile().run(context, args("path", path, "content", content));
            boolean refused = model.containsKey(path) && !fresh.contains(path);
            expect(refused, result, "write " + path);
            if (!refused) {
                model.put(path, content);
                fresh.add(path);
            }
            return "write " + path + " " + quote(content);
        }
        path = pick(random, model.keySet());
        if (op < 50) {
            String content = model.get(path);
            String old;
            int kind = random.nextInt(10);
            if (kind == 0 || content.isEmpty()) {
                old = "missing-" + random.nextInt(1000);
            } else {
                int from = random.nextInt(content.length());
                int to = Math.min(content.length(), from + 1 + random.nextInt(8));
                old = content.substring(from, to);
            }
            String replacement = random.nextInt(4) == 0 ? "" : WORDS.get(random.nextInt(WORDS.size()));
            int count = occurrences(content, old);
            log.add("   (trying) edit " + path + " " + quote(old) + " -> " + quote(replacement) + " (occurs " + count + ", fresh "
                    + fresh.contains(path) + ") in " + quote(content));
            LocalResult result = new EditFile().run(context, args("path", path, "old_string", old, "new_string", replacement));
            boolean ok = fresh.contains(path) && !old.isEmpty() && !old.equals(replacement) && count == 1;
            expect(!ok, result, "edit " + path);
            if (ok) {
                model.put(path, content.replace(old, replacement));
            }
            return "edit " + path + " " + quote(old) + " -> " + quote(replacement) + " (occurs " + count + ")";
        }
        if (op < 70) {
            LocalResult result = new ReadFile().run(context, args("path", path));
            expect(false, result, "read " + path);
            assertEquals(shown(model.get(path)), result.text(), "read " + path);
            fresh.add(path);
            return "read " + path;
        }
        if (op < 78) {
            String dir = DIRS.get(random.nextInt(DIRS.size()));
            LocalResult result = new ListDir().run(context, dir.isEmpty() ? args() : args("path", dir));
            Set<String> expected = listing(dir);
            if (expected == null) {
                expect(true, result, "list " + dir);
            } else {
                assertEquals(expected.isEmpty() ? "(empty directory)" : String.join("\n", expected), result.text(), "list " + dir);
            }
            return "list " + (dir.isEmpty() ? "." : dir);
        }
        if (op < 85) {
            String name = NAMES.get(random.nextInt(NAMES.size()));
            LocalResult result = new FindFiles().run(context, args("pattern", name));
            List<String> expected = model.keySet().stream().filter(each -> each.endsWith("/" + name) || each.equals(name)).sorted().toList();
            // Windows 上显示的路径用 \ 分隔，查的是找到了哪些，所以统一成 /
            assertEquals(expected.isEmpty() ? "No files found." : String.join("\n", expected), result.text().replace('\\', '/'), "find " + name);
            return "find " + name;
        }
        if (op < 93) {
            String word = List.of("needle", "价格", "alpha", "zzz").get(random.nextInt(4));
            LocalResult result = new Grep().run(context, args("pattern", java.util.regex.Pattern.quote(word)));
            TreeSet<String> expected = new TreeSet<>();
            model.forEach((file, content) -> {
                String[] lines = content.split("\n", -1);
                int count = content.endsWith("\n") ? lines.length - 1 : lines.length;
                for (int i = 0; i < count; i++) {
                    if (lines[i].contains(word)) {
                        expected.add(file + ":" + (i + 1) + ":" + lines[i]);
                    }
                }
            });
            assertEquals(expected.isEmpty() ? "No matches." : String.join("\n", expected),
                    expected.isEmpty() ? result.text() : String.join("\n", new TreeSet<>(result.text().replace('\\', '/').lines().toList())), "grep " + word);
            return "grep " + word;
        }
        // 外部改动：用户在别处改了文件，之后改和覆盖都要先重读
        String changed = model.get(path) + "outside\n";
        Path file = root.resolve(path);
        Files.writeString(file, changed);
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().plusSeconds(5)));
        model.put(path, changed);
        fresh.remove(path);
        return "external change " + path;
    }

    private static void expect(boolean error, LocalResult result, String what) {
        assertEquals(error, result.error(), what + " -> " + result.text());
    }

    /** 读出来的样子：去掉最后一个换行（文件只有换行时也一样）。 */
    private static String shown(String content) {
        return content.endsWith("\n") ? content.substring(0, content.length() - 1) : content;
    }

    private Set<String> listing(String dir) {
        if (!dir.isEmpty() && model.keySet().stream().noneMatch(each -> each.startsWith(dir))) {
            return Files.isDirectory(root.resolve(dir)) ? new TreeSet<>() : null;
        }
        TreeSet<String> names = new TreeSet<>();
        for (String each : model.keySet()) {
            if (each.startsWith(dir)) {
                String rest = each.substring(dir.length());
                int slash = rest.indexOf('/');
                names.add(slash < 0 ? rest : rest.substring(0, slash + 1));
            }
        }
        return names;
    }

    private void checkDisk() throws IOException {
        Map<String, String> disk = new TreeMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                disk.put(root.relativize(file).toString().replace('\\', '/'), Files.readString(file));
            }
        }
        assertEquals(model, disk, "the files on disk");
    }

    private static String text(Random random) {
        StringBuilder text = new StringBuilder();
        int lines = random.nextInt(5);
        for (int i = 0; i < lines; i++) {
            int words = 1 + random.nextInt(4);
            for (int j = 0; j < words; j++) {
                text.append(j == 0 ? "" : " ").append(WORDS.get(random.nextInt(WORDS.size())));
            }
            if (i < lines - 1 || random.nextBoolean()) {
                text.append('\n');
            }
        }
        return text.toString();
    }

    /** 能对上的位置数，重叠的也算（只改一处时要唯一）。 */
    private static int occurrences(String text, String part) {
        if (part.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) {
            count++;
        }
        return count;
    }

    private static String pick(Random random, Set<String> keys) {
        List<String> list = new ArrayList<>(keys);
        return list.get(random.nextInt(list.size()));
    }

    private static String quote(String text) {
        return "\"" + text.replace("\n", "\\n").replace("\t", "\\t") + "\"";
    }
}
