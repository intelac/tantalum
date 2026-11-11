package tantalum.shell.skill;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Objects;
import tantalum.value.event.SkillUse;

/**
 * 对话页和轨迹页上，用户消息带的一个点名技能：读到了有 {@code content}（提交那一刻的全文），没读到有 {@code error}（原因的代码）。
 * 另一样不写出。
 */
public record SkillNote(String name, @JsonInclude(JsonInclude.Include.NON_NULL) String content,
                        @JsonInclude(JsonInclude.Include.NON_NULL) String error) {

    public SkillNote {
        Objects.requireNonNull(name, "name");
    }

    public static List<SkillNote> of(List<SkillUse> skills) {
        return skills.stream().map(skill -> new SkillNote(skill.name(), skill.content(), skill.error())).toList();
    }
}
