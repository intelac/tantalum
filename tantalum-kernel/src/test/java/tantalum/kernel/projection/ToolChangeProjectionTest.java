package tantalum.kernel.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.kernel.compaction.Trimming;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;
import tantalum.value.model.Message;
import tantalum.value.model.Prompt;
import tantalum.value.model.Role;
import tantalum.value.model.TextBlock;
import tantalum.value.model.ToolChangeBlock;
import tantalum.value.model.ToolSpec;
import tantalum.value.state.SessionContext;
import tantalum.value.state.SessionState;

/** core-tools C14 和设计第 1 节：投影出的 prompt 里工具全集不变、没开的标 deferred，工具变化总在合法的位置。 */
class ToolChangeProjectionTest {

    private static final ToolSpec GREP = new ToolSpec("grep", "d", new Json.Null());
    private static final ToolSpec WRITE = new ToolSpec("write_file", "d", new Json.Null());
    private static final Message ANSWER = new Message(Role.ASSISTANT, List.of(new TextBlock("ok")), false);

    private static Message change(List<String> added, List<String> removed) {
        return new Message(Role.SYSTEM, List.of(new ToolChangeBlock(added, removed)), false);
    }

    @Test
    void toolsOffAtTheStartAreDeferredAndStaySo() {
        SessionContext context = new SessionContext("sys", List.of(GREP, WRITE), null, List.of(), List.of("grep"), List.of("grep", "write_file"),
                List.of());
        SessionState session = SessionState.start(new SessionId("s"), new Principal("u")).withContext(context);
        Prompt prompt = PromptProjection.project(session);
        // 中途打开了 write_file，工具列表仍和会话开始时逐字节相同：它靠历史里的工具变化出现
        assertEquals(List.of(GREP, WRITE.deferred(true)), prompt.tools());
    }

    @Test
    void oldSessionsHaveNothingDeferred() {
        SessionState session = SessionState.start(new SessionId("s"), new Principal("u"))
                .withContext(new SessionContext("sys", List.of(GREP, WRITE), null, List.of()));
        assertEquals(List.of(GREP, WRITE), PromptProjection.project(session).tools());
    }

    @Test
    void aChangeBetweenTheQuestionAndTheAnswerStaysPut() {
        List<Message> messages = List.of(Message.user("q"), change(List.of("write_file"), List.of()), ANSWER);
        assertEquals(messages, PromptProjection.placeSystemUpdates(messages));
        List<Message> last = List.of(Message.user("q"), change(List.of("write_file"), List.of()));
        assertEquals(last, PromptProjection.placeSystemUpdates(last));
    }

    /** 上一次提问没有回复，变化后面跟着下一次提问：挪到下一处合法的位置，和那里的变化合成净变化。 */
    @Test
    void aChangeWithoutAnAnswerMovesAndMerges() {
        List<Message> messages = List.of(Message.user("q1"), change(List.of("write_file"), List.of()), Message.user("q2"),
                change(List.of("grep"), List.of()), ANSWER);
        assertEquals(List.of(Message.user("q1"), Message.user("q2"), change(List.of("write_file", "grep"), List.of()), ANSWER),
                PromptProjection.placeSystemUpdates(messages));
    }

    @Test
    void aMovedChangeWithNothingToMergeWithLandsBeforeTheNextAnswer() {
        List<Message> messages = List.of(Message.user("q1"), change(List.of(), List.of("grep")), Message.user("q2"), ANSWER);
        assertEquals(List.of(Message.user("q1"), Message.user("q2"), change(List.of(), List.of("grep")), ANSWER),
                PromptProjection.placeSystemUpdates(messages));
        List<Message> waiting = List.of(Message.user("q1"), change(List.of(), List.of("grep")), Message.user("q2"));
        assertEquals(List.of(Message.user("q1"), Message.user("q2"), change(List.of(), List.of("grep"))),
                PromptProjection.placeSystemUpdates(waiting));
    }

    /** 三次提问都没等到回复：开了又关就什么都不剩。 */
    @Test
    void changesThatCancelOutDisappear() {
        List<Message> messages = List.of(Message.user("q1"), change(List.of("write_file"), List.of()), Message.user("q2"),
                change(List.of(), List.of("write_file")), Message.user("q3"), change(List.of("grep"), List.of()), ANSWER);
        assertEquals(List.of(Message.user("q1"), Message.user("q2"), Message.user("q3"), change(List.of("grep"), List.of()), ANSWER),
                PromptProjection.placeSystemUpdates(messages));
        List<Message> nothing = List.of(Message.user("q1"), change(List.of("write_file"), List.of()), Message.user("q2"),
                change(List.of(), List.of("write_file")), ANSWER);
        assertEquals(List.of(Message.user("q1"), Message.user("q2"), ANSWER), PromptProjection.placeSystemUpdates(nothing));
    }

    /** 已经放对的部分在后面追加新消息时不动：前缀稳定，签名才继续有效。 */
    @Test
    void placedPrefixesNeverMove() {
        List<Message> before = List.of(Message.user("q1"), change(List.of("write_file"), List.of()), ANSWER);
        List<Message> after = List.of(Message.user("q1"), change(List.of("write_file"), List.of()), ANSWER, Message.user("q2"),
                change(List.of(), List.of("grep")), ANSWER);
        assertEquals(PromptProjection.placeSystemUpdates(before), PromptProjection.placeSystemUpdates(after).subList(0, 3));
    }

    @Test
    void sessionsWithoutChangesAreUntouched() {
        List<Message> messages = List.of(Message.user("q1"), ANSWER, Message.user("q2"));
        assertEquals(messages, PromptProjection.placeSystemUpdates(messages));
    }

    /** 压缩时保留段里的工具变化和推理块一起去掉。 */
    @Test
    void compactionDropsToolChanges() {
        assertEquals(List.of(Message.user("q"), ANSWER),
                Trimming.trimAll(List.of(Message.user("q"), change(List.of("grep"), List.of()), ANSWER), 1000));
    }
    /** hook-patch 需求 1.5：没等到回复的钩子说明和工具变化一起往后并成一条，文字在前、净变化在后。 */
    @Test
    void anUnansweredHookTextMergesIntoTheNextUpdate() {
        Message note = new Message(Role.SYSTEM, List.of(new tantalum.value.model.HookTextBlock("q", "80%")), false);
        Message later = new Message(Role.SYSTEM, List.of(new tantalum.value.model.HookTextBlock("q", "40%"),
                new ToolChangeBlock(List.of("cite"), List.of())), false);
        List<Message> messages = List.of(Message.user("q1"), note, change(List.of("write_file"), List.of()), Message.user("q2"), later, ANSWER);
        assertEquals(List.of(Message.user("q1"), Message.user("q2"), new Message(Role.SYSTEM, List.of(
                new tantalum.value.model.HookTextBlock("q", "80%"), new tantalum.value.model.HookTextBlock("q", "40%"),
                new ToolChangeBlock(List.of("write_file", "cite"), List.of())), false), ANSWER),
                PromptProjection.placeSystemUpdates(messages));
    }
}
