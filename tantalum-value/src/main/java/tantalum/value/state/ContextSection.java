package tantalum.value.state;

import java.util.Objects;

/** system 里会变的一段。{@code byLine} 为真时按行比较变化（如记忆索引），否则变了就整段重发。{@code body} 已经转义，可以直接放进标签。 */
public record ContextSection(String name, String body, boolean byLine) {

    public ContextSection {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(body, "body");
    }

    /** 放进 system 和变更说明时的样子：用段名做标签包起来。 */
    public String tagged() {
        return "<" + name + ">\n" + body + "\n</" + name + ">";
    }
}
