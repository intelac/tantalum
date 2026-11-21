package tantalum.value.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.value.decision.SessionChange;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;
import tantalum.value.model.Message;

/**
 * history-window 需求 1、2：压缩完成时去掉边界之前的消息，模型看到的不变；位置一律从头数；去掉记得下、套得回，只能去掉这一步
 * 开始时就有的开头。
 */
class HistoryWindowTest {

    private static final SessionId S = new SessionId("s");
    private static final Principal U = new Principal("u");

    /** q1 a1 q2 a2，压缩成“摘要 + a2”，边界在 4。 */
    private static SessionState compacted() {
        SessionState state = SessionStateFixture.regular(S, U);
        for (String text : List.of("q1", "a1", "q2", "a2")) {
            state = state.append(Message.user(text));
        }
        return state.compacted(new Compaction(List.of(Message.user("summary"), Message.user("a2")), 4));
    }

    @Test
    void droppingBeforeTheBoundaryLeavesWhatTheModelSeesAlone() {
        SessionState full = compacted().append(Message.user("q3"));
        SessionState window = full.dropBefore(4);

        assertEquals(4, window.windowStart());
        assertEquals(List.of(Message.user("q3")), window.messages());
        assertEquals(5, window.historySize());
        assertEquals(full.view(), window.view());
        assertEquals(Message.user("q3"), window.said(4));
        assertThrows(IllegalArgumentException.class, () -> window.said(3), "a dropped message is not read as another one");
        assertThrows(IllegalArgumentException.class, () -> window.dropBefore(3), "the window only moves forward");
        assertThrows(IllegalArgumentException.class, () -> window.dropBefore(6));
    }

    /** 撤下文字记的位置从头数：压缩、去掉之后照样对得上。 */
    @Test
    void withdrawalsCountFromTheFirstMessage() {
        SessionState window = compacted().append(Message.user("my plate 7Q2KD")).dropBefore(4);
        SessionState withdrawn = window.withdrawing(List.of(new Withdrawal("7Q2KD", "[withdrawn]", window.historySize(), 9)),
                window.inbox(), window.compaction(), window.context()).append(Message.user("7Q2KD again"));

        assertEquals(Message.user("my plate [withdrawn]"), withdrawn.said(4));
        assertEquals(Message.user("7Q2KD again"), withdrawn.said(5), "what is said after the withdrawal is not touched");
    }

    @Test
    void theChangeRecordsTheDropAndTheAppendsAndPutsThemBack() {
        SessionState before = compacted();
        SessionState after = before.append(Message.user("q3")).dropBefore(4).append(Message.user("a3"));

        SessionChange change = SessionChange.between(before, after);
        assertEquals(new SessionChange.Changed<>(4), change.windowStart());
        assertEquals(List.of(Message.user("q3"), Message.user("a3")), change.appended());
        assertEquals(after, change.applyTo(before));

        assertNull(SessionChange.between(after, after.append(Message.user("q4"))).windowStart(), "no drop, nothing written");
    }

    /** 只能去掉这一步开始时就有的：这一步刚追加的不能一起去掉（否则账里没有它们）。 */
    @Test
    void aStepCannotDropWhatItAppended() {
        SessionState before = compacted();
        SessionState dropsItsOwn = before.append(Message.user("q3"))
                .compacted(new Compaction(List.of(Message.user("summary"), Message.user("q3")), 5)).dropBefore(5);
        assertThrows(IllegalStateException.class, () -> SessionChange.between(before, dropsItsOwn));
    }

    @Test
    void theWindowNeverMovesBack() {
        SessionState window = compacted().append(Message.user("q3")).dropBefore(4);
        SessionState back = new SessionState(window.id(), window.principal(), window.kind(), window.helper(), window.messages(), 3,
                window.activeRun(), window.lastRun(), window.inbox(), window.context(), null, window.anchor(), window.omittedImages(),
                window.toolsSeen(), window.notes(), window.model(), window.dir(), window.offRecord(), window.assignments(), window.withdrawn(), window.outbox(), window.issuer());
        assertThrows(IllegalStateException.class, () -> SessionChange.between(window, back));
    }

    @Test
    void theBoundaryLiesInsideTheWindow() {
        SessionState window = compacted().dropBefore(4);
        assertThrows(IllegalArgumentException.class, () -> window.compacted(new Compaction(List.of(), 3)));
    }
}
