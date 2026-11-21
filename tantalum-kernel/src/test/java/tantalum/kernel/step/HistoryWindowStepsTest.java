package tantalum.kernel.step;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tantalum.kernel.LoopFixture.text;

import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.kernel.LoopFixture;
import tantalum.kernel.Replay;
import tantalum.value.event.ModelReplied;
import tantalum.value.event.TextWithdrawn;
import tantalum.value.event.TextWithdrawn.By;
import tantalum.value.event.TextWithdrawn.Swap;
import tantalum.value.id.RequestId;
import tantalum.value.model.StopReason;
import tantalum.value.model.TokenUsage;
import tantalum.value.state.KernelState;

/**
 * history-window：压缩完成以后，状态里只留模型看得到的那一段；撤下压缩前说过的话照样记下；回放一样。
 * （同一步里先追加再只裁剪地压缩，追加的留在窗口里：由 {@code LoopFixture} 每一步核对结果行、
 * {@code CompactStepsTest.hugeToolResultWithASmallHeadIsTrimmedWithoutASummaryCall} 守着。）
 */
class HistoryWindowStepsTest {

    /** 两轮问答后手动压缩：摘要回来时，压缩前的原话离开状态。 */
    private static LoopFixture compactedByHand() {
        LoopFixture loop = CompactStepsTest.idleAfterTwoRuns();
        loop.feed(null, CompactStepsTest.compactRequest(null));
        var summary = CompactStepsTest.onlyCall(loop);
        loop.feed(loop.runId(), new ModelReplied(summary.id(), text("Goal: answer"), StopReason.END_TURN, "m", new TokenUsage(30, 10, 0, 0),
                null, null, null));
        return loop;
    }

    @Test
    void afterACompactionTheStateKeepsWhatTheModelSees() {
        LoopFixture loop = compactedByHand();
        var session = loop.session();
        assertTrue(session.messages().isEmpty(), session.messages().toString());
        assertEquals(4, session.windowStart());
        assertEquals(session.compaction().tailFrom(), session.windowStart());
        assertEquals(session.compaction().replacement(), session.view(), "the model sees the replacement, as before");
    }

    /** 原话只在去掉的部分里（账里还有）：撤下照样记下，插件读账时据此换掉；同样的再撤一次什么都不改。 */
    @Test
    void textSaidOnlyBeforeTheBoundaryIsStillWithdrawn() {
        LoopFixture loop = compactedByHand();
        String said = CompactStepsTest.block("q1").substring(0, 20);
        loop.feed(null, new TextWithdrawn(new RequestId("w1"), List.of(new Swap(said, "[withdrawn]")), By.user()));

        assertEquals(1, loop.session().withdrawn().size());
        assertEquals(said, loop.session().withdrawn().getFirst().original());
        assertEquals(loop.session().historySize(), loop.session().withdrawn().getFirst().before());

        KernelState before = loop.state;
        loop.feed(null, new TextWithdrawn(new RequestId("w2"), List.of(new Swap(said, "[withdrawn]")), By.user()));
        assertSame(before, loop.state, "the same withdrawal again changes nothing");
    }

    /** 没压缩过的会话照旧：哪里都没有的文字，撤下什么都不改。 */
    @Test
    void withoutACompactionTextThatIsNowhereChangesNothing() {
        LoopFixture loop = CompactStepsTest.idleAfterTwoRuns();
        KernelState before = loop.state;
        loop.feed(null, new TextWithdrawn(new RequestId("w1"), List.of(new Swap("never said", "[withdrawn]")), By.user()));
        assertSame(before, loop.state);
    }

    @Test
    void replayGivesTheSameWindow() {
        LoopFixture loop = compactedByHand();
        loop.prompt("after the compaction");
        assertEquals(loop.state, Replay.run(loop.kernel, loop.journal).state());
    }
}
