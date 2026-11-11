package tantalum.value.state;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tantalum.value.model.Message;

/** input-area 需求 1.11：排队的提问按“位置加原文”定位。 */
class QueueTest {

    private static final List<Message> QUEUE = List.of(Message.user("a"), Message.user("b"), Message.user("a"), Message.user("c"));

    @ParameterizedTest(name = "{0}: index {1}, expected {2} -> {3}")
    @CsvSource({
        "still in place,                 1, b, 1",
        "moved towards the head,         3, b, 1",
        "index beyond the end,           9, c, 3",
        "same text twice takes the near, 2, a, 2",
        "same text twice from the first, 1, a, 0",
        "never looks behind the index,   2, c, -1",
        "not there,                      3, z, -1",
        "negative index,                -1, a, -1",
    })
    void locate(String name, int index, String expected, int found) {
        assertEquals(found, Queue.locate(QUEUE, index, Message.user(expected)));
    }

    @org.junit.jupiter.api.Test
    void anEmptyQueueHasNothing() {
        assertEquals(-1, Queue.locate(List.of(), 0, Message.user("a")));
    }
}
