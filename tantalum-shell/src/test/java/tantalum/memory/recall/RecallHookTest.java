package tantalum.memory.recall;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import tantalum.value.delegate.DelegateCall;
import tantalum.value.id.SessionId;

/** 拿去找记忆的那句话：别的会话交来的活、回来的结果，只用内容本身，不带给模型看的固定开场白。 */
class RecallHookTest {

    @Test
    void theHandOffPreambleIsNotPartOfTheQuestion() {
        String handed = DelegateCall.text(new DelegateCall.Parsed("count the files", "only under src", null, null, false), new SessionId("s-a"));
        assertEquals("count the files\n\nonly under src", RecallHook.plain(handed));
        assertEquals("42 files", RecallHook.plain(DelegateCall.REPORT + "Conversation s-b finished:\n42 files"));
        assertEquals("what did we decide", RecallHook.plain("what did we decide"));
    }
}
