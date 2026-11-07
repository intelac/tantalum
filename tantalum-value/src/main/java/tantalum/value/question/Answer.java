package tantalum.value.question;

/**
 * 用户对一个 {@link Question} 的回答：选的选项 id、补的文字，至少一个非空；两者都为空的回答不合法（{@link Question#accepts}）。
 * 空字符串当作没有。
 */
public record Answer(String choice, String text) {

    public Answer {
        choice = choice == null || choice.isBlank() ? null : choice;
        text = text == null || text.isEmpty() ? null : text;
        if (choice == null && text == null) {
            throw new IllegalArgumentException("an answer needs a choice or text");
        }
    }

    public static Answer of(String choice) {
        return new Answer(choice, null);
    }
}
