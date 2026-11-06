package tantalum.shell.plugin;

import java.util.List;
import java.util.Objects;

/**
 * 在控制台上问用户的一个问题（{@link Questions}）。{@code id} 在这个用户的这个会话里唯一，同一个问题再问一次用同一个 id；
 * {@code detail} 可以为空；{@code choices} 至少一个，按钮按这个顺序显示。
 */
public record Question(String id, String title, String detail, List<Choice> choices) {

    /** 一个选项：{@code id} 是回答时交回来的值，{@code label} 是按钮上的字。 */
    public record Choice(String id, String label) {

        public Choice {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(label, "label");
        }
    }

    public Question {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        detail = detail == null ? "" : detail;
        choices = List.copyOf(choices);
        if (choices.isEmpty()) {
            throw new IllegalArgumentException("a question needs at least one choice");
        }
    }

    /** {@code choice} 是不是这个问题的选项。 */
    public boolean offers(String choice) {
        return choices.stream().anyMatch(option -> option.id().equals(choice));
    }
}
