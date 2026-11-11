package tantalum.value.event;

import java.util.Objects;

/**
 * 用户在一条提交里点名的一个技能（消息里的 {@code /name}）。读到了：{@code content} 是提交那一刻的 {@code <skill_content>} 整块，
 * 之后技能文件再改也不变；没读到：{@code content} 为 null，{@code error} 是原因的代码（如 {@code timeout}、{@code unreadable}）。
 */
public record SkillUse(String name, String content, String error) {

    public SkillUse {
        Objects.requireNonNull(name, "name");
        if ((content == null) == (error == null)) {
            throw new IllegalArgumentException("a named skill has either its content or the reason it could not be read");
        }
    }

    public boolean loaded() {
        return content != null;
    }
}
