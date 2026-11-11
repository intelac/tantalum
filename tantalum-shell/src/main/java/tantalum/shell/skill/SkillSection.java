package tantalum.shell.skill;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import tantalum.local.wire.DirState;
import tantalum.local.wire.SkillList;

/**
 * 给模型的技能清单一段：每个技能一行名字和描述，不给路径。措辞逐句来自 DSH 的 Skill 目录模板。
 * 纯函数：同一批技能每次得到逐字节相同的文字，清单不变时提示词缓存不失效。
 */
public final class SkillSection {

    public static final String NAME = "skills";
    public static final int MAX_DESCRIPTION = 500;
    static final String HEAD = """
            ## Skills

            A skill is a reusable set of task-specific instructions. The following skills are available in this session:

            <available_skills>
            """;
    static final String TAIL = """
            </available_skills>

            If the user names a skill, or the task clearly matches a skill's description, call the `skill` tool with the exact skill name \
            before taking task actions. Load all applicable skills, then follow their full instructions. This list contains summaries only; \
            do not infer or follow a skill's instructions until it has been loaded.
            A user may also invoke a skill directly; its <skill_content> block then appears in this conversation. Follow it, and do not \
            call the `skill` tool again for that skill.""";
    private static final Pattern NAME_RULE = Pattern.compile("[a-z0-9-]{1,64}");
    private static final Pattern BLANKS = Pattern.compile("\\s+");

    private SkillSection() {}

    /** 没有可列的技能时是空串：目录不可用、都被关了、都不让模型调。{@code off} 是用户关掉的名字。 */
    public static String text(SkillList skills, Set<String> off) {
        if (skills == null || skills.state() != DirState.OK) {
            return "";
        }
        List<SkillList.Item> shown = skills.items().stream()
                .filter(item -> item.model() && !off.contains(item.name()) && NAME_RULE.matcher(item.name()).matches())
                .sorted(Comparator.comparing(SkillList.Item::name))
                .toList();
        if (shown.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder(HEAD);
        for (SkillList.Item item : shown) {
            text.append("- `").append(item.name()).append("`: ").append(description(item.description())).append('\n');
        }
        return text.append(TAIL).toString();
    }

    /** 连续空白压成一个空格，超过 500 个字符截到 500（以省略号结尾），再转义。 */
    static String description(String raw) {
        String flat = BLANKS.matcher(raw).replaceAll(" ").strip();
        if (flat.codePointCount(0, flat.length()) > MAX_DESCRIPTION) {
            flat = flat.substring(0, flat.offsetByCodePoints(0, MAX_DESCRIPTION - 1)).stripTrailing() + "…";
        }
        return escape(flat);
    }

    /** 转义之后文字里没有 {@code <}，跳不出清单的标签，也跳不出这一段。 */
    public static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
