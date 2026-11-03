package tantalum.kernel.hook;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.kernel.compaction.SummaryPrompt;
import tantalum.kernel.projection.ContextUpdate;
import tantalum.value.model.Message;
import tantalum.value.model.Role;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolResultBlock;

class HookMessagesTest {

    @Test
    void userTextCounts() {
        assertTrue(HookMessages.isUserText(Message.user("keep this")));
    }

    @Test
    void toolResultDoesNotCount() {
        assertFalse(HookMessages.isUserText(new Message(Role.USER, List.of(new ToolResultBlock("c", "keep this", false, null)), false)));
    }

    @Test
    void contextUpdateDoesNotCount() {
        assertFalse(HookMessages.isUserText(ContextUpdate.message("keep this")));
    }

    /** 摘要也是用户角色的文字消息，但不是用户写的。 */
    @Test
    void summaryDoesNotCount() {
        assertFalse(HookMessages.isUserText(SummaryPrompt.summaryMessage("keep this")));
    }

    @Test
    void assistantTextDoesNotCount() {
        assertFalse(HookMessages.isUserText(new Message(Role.ASSISTANT, List.of(new TextBlock("keep this")), false)));
    }
}
