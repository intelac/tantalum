package tantalum.value.event;

import java.util.Objects;
import tantalum.value.id.SessionId;
import tantalum.value.model.ModelChoice;

/**
 * 另一个会话交代的一件事。不是人说的话：只由 {@code Assign} 的处理器放进来。{@code id} 是这次交代的编号，
 * {@code issuer} 是交代它的会话，结果交回那里；{@code root} 是交代它的那一轮追到头是谁引起的，原样传下来；
 * {@code prompt} 是交代的全部内容；{@code model} 是这一轮用的模型（没定为 null）。
 */
public record Assignment(String id, SessionId issuer, Root root, String prompt, ModelChoice model) implements Input {

    public static final String TYPE = "Assignment";

    public Assignment {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(prompt, "prompt");
    }

    @Override
    public Kind kind() {
        return Kind.ASSIGNMENT;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public int version() {
        return 1;
    }
}
