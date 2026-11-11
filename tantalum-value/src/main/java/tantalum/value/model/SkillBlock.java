package tantalum.value.model;

import java.util.Objects;

/** 用户消息里的一块技能内容：用户点名的技能在提交那一刻的 {@code <skill_content>} 整块，排在文字和附件之后。 */
public record SkillBlock(String name, String text) implements ContentBlock {

    public SkillBlock {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(text, "text");
    }
}
