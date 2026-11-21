package tantalum.kernel.compaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.value.model.Message;

/**
 * 规则 5（压缩后原样留下最近一次请求）也认帮手收到的交代（审查：交代改成专门的块以后，帮手一压缩就只剩摘要，丢了要做的全部内容）。
 * 交代不是你说的话，{@link Compactor#isUserText} 照旧不认它。
 */
class RequestKeptTest {

    @Test
    void aHelpersAssignmentIsTheRequestKeptWordForWord() {
        Message assignment = Message.assignment("t3-0", "Check module A.\nReport every file you read.");
        List<Message> head = List.of(assignment, Message.user("[tool output]"), new Message(tantalum.value.model.Role.ASSISTANT, List.of(new tantalum.value.model.TextBlock("working")), false));
        assertEquals(assignment, Compactor.request(List.of(assignment, new Message(tantalum.value.model.Role.ASSISTANT, List.of(new tantalum.value.model.TextBlock("working")), false))));
        assertTrue(Compactor.isRequest(assignment));
        assertFalse(Compactor.isUserText(assignment), "an assignment is not the user's words");
        assertEquals(Message.user("[tool output]"), Compactor.request(head), "a later user message is still the latest request");
    }
}
