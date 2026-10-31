package tantalum.value.model;

import java.util.Objects;

/** 普通文本。 */
public record TextBlock(String text) implements ContentBlock {

    public TextBlock {
        Objects.requireNonNull(text, "text");
    }
}
