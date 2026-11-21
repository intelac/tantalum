package tantalum.value.model;

import java.util.Objects;

/** 历史里的一笔（dispatch）：别的会话里发生的事。不是你对这个会话说的话。 */
public record NoticeBlock(String from, String text) implements ContentBlock {

    public NoticeBlock {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(text, "text");
    }

    /** 发给模型的文字，两种协议相同。 */
    public String shown() {
        return "[Note from conversation " + from + ", not a request to you: the user talked there directly. Nothing to do unless it matters"
                + " for what you are tracking.]\n" + text;
    }
}
