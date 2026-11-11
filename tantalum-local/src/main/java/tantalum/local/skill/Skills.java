package tantalum.local.skill;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import tantalum.local.fs.AllowedDirs;
import tantalum.local.fs.UserDir;
import tantalum.local.wire.DirState;
import tantalum.local.wire.SkillList;

/**
 * 用户电脑上一个目录里的技能（skills-wiki 需求 1、2、4）：直接子目录里有 {@code SKILL.md} 的算一个，不往下找（DSH-fs“skill 格式”、
 * pi {@code package-manager.ts:426-450}）。每次都当场读，不留缓存：用户改了文件，下一次列、下一次加载就是新的。
 * 模型加载和用户点名走同一个 {@link #load}，得到同一份文字。
 */
public final class Skills {

    /** 谁要加载：模型调 {@code skill} 工具，还是用户在消息里写 {@code /name}。 */
    public enum Caller { MODEL, USER }

    /** 没加载成的原因。 */
    public enum Failure {
        /** 名字不合规则。 */
        INVALID_NAME,
        /** 没有这个技能（或目录用不了）。 */
        UNKNOWN,
        /** 技能写了 {@code disable-model-invocation: true}，模型不能自己加载。 */
        NOT_FOR_MODEL,
        /** 技能写了 {@code user-invocable: false}，用户不能点名。 */
        NOT_FOR_USER,
        /** 有这个名字的子目录，但 {@code SKILL.md} 没通过校验；{@link Loaded#code()} 是原因。 */
        BROKEN
    }

    /** 加载的结果：{@code content} 是 {@code <skill_content>} 整块；失败时为 null，{@code failure} 说原因。 */
    public record Loaded(String content, Failure failure, String code) {

        static Loaded failed(Failure failure) {
            return new Loaded(null, failure, null);
        }
    }

    private static final Pattern CLOSING = Pattern.compile("(?i)</(skill_content|skill_instructions)");

    private Skills() {}

    /** 列 {@code dir} 里的技能。目录用不了时 {@link SkillList#state()} 说是哪一种。 */
    public static SkillList list(AllowedDirs dirs, String dir) {
        Scan scan = scan(dirs, dir);
        if (scan.state != DirState.OK) {
            return SkillList.unusable(scan.state);
        }
        return new SkillList(scan.root.toString(), DirState.OK,
                scan.skills.values().stream().map(SkillFile::item).toList(), scan.problems);
    }

    /** 当场重新读名叫 {@code name} 的技能，拼成给模型的那一块（DSH-tool“Skill 结果模板”，逐字）。 */
    public static Loaded load(AllowedDirs dirs, String dir, String name, Caller caller) {
        if (!SkillFile.validName(name)) {
            return Loaded.failed(Failure.INVALID_NAME);
        }
        Scan scan = scan(dirs, dir);
        if (scan.state != DirState.OK) {
            return Loaded.failed(Failure.UNKNOWN);
        }
        SkillFile skill = scan.skills.get(name);
        if (skill == null) {
            return scan.problems.stream()
                    .filter(problem -> problem.folder().equals(name) && !problem.code().equals("name-duplicate"))
                    .findFirst()
                    .map(problem -> new Loaded(null, Failure.BROKEN, problem.code()))
                    .orElse(Loaded.failed(Failure.UNKNOWN));
        }
        if (caller == Caller.MODEL && !skill.model()) {
            return Loaded.failed(Failure.NOT_FOR_MODEL);
        }
        if (caller == Caller.USER && !skill.user()) {
            return Loaded.failed(Failure.NOT_FOR_USER);
        }
        return new Loaded(content(skill), null, null);
    }

    /**
     * 正文是文件内容，原样给模型，只把会提前关掉这一块的两种闭合标签改掉：技能里常有 HTML、XML 的例子，不能全篇转义。
     * 路径里不会有 {@code </}。
     */
    static String content(SkillFile skill) {
        return "<skill_content name=\"" + skill.name() + "\">\n"
                + "<skill_resources>\n"
                + "Base directory for this skill: " + skill.base() + "\n"
                + "Resolve relative paths mentioned by this skill against the base directory before using them. "
                + "Load referenced resources only as needed.\n"
                + "</skill_resources>\n"
                + "\n"
                + "<skill_instructions>\n"
                + CLOSING.matcher(skill.body()).replaceAll("<\\\\/$1") + "\n"
                + "</skill_instructions>\n"
                + "</skill_content>";
    }

    private static final class Scan {

        final DirState state;
        Path root;
        /** 按名字排序。 */
        final Map<String, SkillFile> skills = new TreeMap<>();
        final List<SkillList.Problem> problems = new ArrayList<>();

        Scan(DirState state) {
            this.state = state;
        }
    }

    private static Scan scan(AllowedDirs dirs, String dir) {
        UserDir located = UserDir.locate(dirs, dir);
        if (located.state() != DirState.OK) {
            return new Scan(located.state());
        }
        List<Path> folders = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(located.path())) {
            for (Path entry : entries) {
                String folder = entry.getFileName().toString();
                if (!folder.startsWith(".") && !folder.equals("node_modules") && Files.isDirectory(entry)) {
                    folders.add(entry);
                }
            }
        } catch (IOException failure) {
            return new Scan(DirState.UNREADABLE);
        }
        // 重名时目录名排在前面的赢（pi skills.ts:437-449：先发现的赢），所以先排序再读
        folders.sort(Comparator.comparing(folder -> folder.getFileName().toString()));
        Scan scan = new Scan(DirState.OK);
        scan.root = located.path();
        Map<String, String> owners = new LinkedHashMap<>();
        for (Path entry : folders) {
            String folder = entry.getFileName().toString();
            Path file = entry.resolve(SkillFile.FILE);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            Path real;
            try {
                // 子目录或 SKILL.md 是指到范围外的符号链接时不读
                real = dirs.resolve(file.toString());
            } catch (IllegalArgumentException outside) {
                scan.problems.add(new SkillList.Problem(folder, "outside", null));
                continue;
            }
            try {
                SkillFile skill = SkillFile.read(real, folder);
                String owner = owners.putIfAbsent(skill.name(), folder);
                if (owner != null) {
                    scan.problems.add(new SkillList.Problem(folder, "name-duplicate", skill.name()));
                } else {
                    scan.skills.put(skill.name(), skill);
                }
            } catch (SkillFile.Rejected rejected) {
                scan.problems.add(new SkillList.Problem(folder, rejected.code, rejected.detail));
            }
        }
        return scan;
    }
}
