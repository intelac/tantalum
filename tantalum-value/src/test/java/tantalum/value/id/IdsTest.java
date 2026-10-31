package tantalum.value.id;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class IdsTest {

    private static final List<Function<String, Object>> ALL = List.of(
            Principal::new, SessionId::new, RunId::new, CommandId::new, RequestId::new, SubmissionId::new);

    @Test
    void equalValuesAreEqual() {
        ALL.forEach(id -> assertEquals(id.apply("a"), id.apply("a")));
        ALL.forEach(id -> assertNotEquals(id.apply("a"), id.apply("b")));
    }

    @Test
    void nullAndBlankAreRejected() {
        ALL.forEach(id -> {
            assertThrows(NullPointerException.class, () -> id.apply(null));
            assertThrows(IllegalArgumentException.class, () -> id.apply(" "));
        });
    }
}
