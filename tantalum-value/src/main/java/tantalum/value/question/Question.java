package tantalum.value.question;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import tantalum.value.text.LocalText;

/**
 * 问用户的一个问题（waiting-state）。{@code id} 在一个运行里唯一，同一个问题再问一次用同一个 id；{@code detail} 可以为空；
 * {@code choices} 按钮按这个顺序显示，只有允许自由文本（{@code freeText}）时才可以没有选项；{@code deadline} 是到期时刻，
 * 没有期限为 null。标题、说明和按钮上的字是 {@link LocalText}，控制台按看的人的语言选。
 */
public record Question(String id, LocalText title, LocalText detail, List<Choice> choices, boolean freeText, Instant deadline) {

    /** 自由文本的上限（需求 5.5）。 */
    public static final int MAX_TEXT = 4000;

    /** 一个选项：{@code id} 是回答时交回来的值，{@code label} 是按钮上的字。 */
    public record Choice(String id, LocalText label) {
        public Choice {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(label, "label");
            if (id.isBlank()) {
                throw new IllegalArgumentException("a choice needs an id");
            }
        }
    }

    public Question {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        if (id.isBlank()) {
            throw new IllegalArgumentException("a question needs an id");
        }
        detail = detail == null ? LocalText.empty() : detail;
        choices = List.copyOf(choices);
        if (choices.isEmpty() && !freeText) {
            throw new IllegalArgumentException("a question needs at least one choice unless it allows free text");
        }
        if (choices.stream().map(Choice::id).distinct().count() != choices.size()) {
            throw new IllegalArgumentException("choice ids must be distinct");
        }
    }

    /** 只有选项、没有期限的问题（插件的 {@code Ask} 用）。 */
    public Question(String id, LocalText title, LocalText detail, List<Choice> choices) {
        this(id, title, detail, choices, false, null);
    }

    public Question withDeadline(Instant newDeadline) {
        return new Question(id, title, detail, choices, freeText, newDeadline);
    }

    /** {@code choice} 是不是这个问题的选项。 */
    public boolean offers(String choice) {
        return choice != null && choices.stream().anyMatch(option -> option.id().equals(choice));
    }

    /** 这个回答对这个问题合不合法：选项要是列出的；文字只有允许时才能有、不超过上限；两者至少一个。 */
    public boolean accepts(Answer answer) {
        if (answer == null) {
            return false;
        }
        boolean hasChoice = answer.choice() != null;
        boolean hasText = answer.text() != null;
        if (!hasChoice && !hasText) {
            return false;
        }
        if (hasChoice && !offers(answer.choice())) {
            return false;
        }
        if (hasText && (!freeText || answer.text().length() > MAX_TEXT)) {
            return false;
        }
        return hasChoice || !choices.isEmpty() || freeText;
    }
}
