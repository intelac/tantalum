package tantalum.shell.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.HookTextBlock;
import tantalum.value.model.Message;
import tantalum.value.model.ModelChangeBlock;
import tantalum.value.model.Role;
import tantalum.value.model.SystemUpdates;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolChangeBlock;
import tantalum.value.model.ToolResultBlock;

/** review-fixes 需求 12.2：换模型的说明和钩子的说明怎么并进用户消息，一个折叠器。 */
class FoldingTest {

    private static final Message USER = Message.user("hi");
    private static final Message ASSISTANT = new Message(Role.ASSISTANT, List.of(new TextBlock("hello")), false);
    private static final Message SWITCH = new Message(Role.SYSTEM, List.of(new ModelChangeBlock("a", "b")), false);
    private static final Message HOOK = new Message(Role.SYSTEM,
            List.of(new HookTextBlock("quota", "80% left"), new ToolChangeBlock(List.of("grep"), List.of())), false);
    private static final String SWITCH_TEXT = new ModelChangeBlock("a", "b").text();

    private static Message user(ContentBlock... blocks) {
        return new Message(Role.USER, List.of(blocks), false);
    }

    @Test
    void withoutSystemMessagesTheListIsReturnedAsIs() {
        List<Message> plain = List.of(USER, ASSISTANT);
        assertSame(plain, Folding.fold(plain, true));
        assertEquals(List.of(USER, HOOK), Folding.fold(List.of(USER, HOOK), false), "hook texts stay when they are sent as system");
    }

    @Test
    void aModelChangeGoesToTheFrontOfTheUserMessageOrAfterToolResults() {
        assertEquals(List.of(user(new TextBlock(SWITCH_TEXT + "\n\n"), new TextBlock("hi")), ASSISTANT),
                Folding.fold(List.of(USER, SWITCH, ASSISTANT), false));
        Message results = user(new ToolResultBlock("t1", "out", false, null));
        assertEquals(List.of(user(new ToolResultBlock("t1", "out", false, null), new TextBlock(SWITCH_TEXT))),
                Folding.fold(List.of(results, SWITCH), false));
        // 前面不是用户消息：单独成一条；两条说明按顺序排在用户的话前面
        assertEquals(List.of(ASSISTANT, user(new TextBlock(SWITCH_TEXT))), Folding.fold(List.of(ASSISTANT, SWITCH), false));
        Message second = new Message(Role.SYSTEM, List.of(new ModelChangeBlock("b", "c")), false);
        assertEquals(List.of(user(new TextBlock(SWITCH_TEXT + "\n\n"), new TextBlock(new ModelChangeBlock("b", "c").text() + "\n\n"), new TextBlock("hi"))),
                Folding.fold(List.of(USER, SWITCH, second), false));
    }

    @Test
    void hookTextsGoToTheEndOfTheUserMessageAndToolChangesAreDropped() {
        assertEquals(List.of(user(new TextBlock("hi"), new TextBlock(SystemUpdates.HEADER + "80% left")), ASSISTANT),
                Folding.fold(List.of(USER, HOOK, ASSISTANT), true));
        assertEquals(List.of(ASSISTANT, user(new TextBlock(SystemUpdates.HEADER + "80% left"))), Folding.fold(List.of(ASSISTANT, HOOK), true));
        Message onlyTools = new Message(Role.SYSTEM, List.of(new ToolChangeBlock(List.of("grep"), List.of())), false);
        assertEquals(List.of(USER), Folding.fold(List.of(USER, onlyTools), true), "nothing to say: the message disappears");
    }

    @Test
    void bothKindsOnTheSameUserMessageKeepTheirPlaces() {
        assertEquals(List.of(user(new TextBlock(SWITCH_TEXT + "\n\n"), new TextBlock("hi"), new TextBlock(SystemUpdates.HEADER + "80% left"))),
                Folding.fold(List.of(USER, SWITCH, HOOK), true));
        // 钩子说明之后再来换模型的说明：对它来说前面那条是系统消息，单独成一条（和原来两步折叠的结果相同）
        assertEquals(List.of(user(new TextBlock("hi"), new TextBlock(SystemUpdates.HEADER + "80% left")), user(new TextBlock(SWITCH_TEXT))),
                Folding.fold(List.of(USER, HOOK, SWITCH), true));
    }
}
