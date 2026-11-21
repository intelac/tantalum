package tantalum.kernel.step;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tantalum.kernel.LoopFixture;
import tantalum.value.event.Cancel;
import tantalum.value.event.ModelReplied;
import tantalum.value.id.CommandId;
import tantalum.value.id.RequestId;
import tantalum.value.id.RunId;
import tantalum.value.model.StopReason;
import tantalum.value.model.TokenUsage;
import tantalum.value.state.KernelState;

/** live-views 需求 1：一轮结束时去掉同会话更早结束的运行；点名被去掉的运行的迟到事件什么都不改。 */
class EndedRunsStepsTest {

    private static Set<RunId> runs(LoopFixture loop) {
        return loop.state.runs().keySet().stream().collect(Collectors.toSet());
    }

    @Test
    void onlyTheLatestEndedRunStays() {
        LoopFixture loop = CompactStepsTest.start();
        RunId first = loop.runId();
        loop.feed(first, CompactStepsTest.endTurn(loop, "a1", 100));
        assertEquals(Set.of(first), runs(loop), "the only run stays after it ends");

        loop.prompt("q2");
        RunId second = loop.runId();
        assertEquals(Set.of(first, second), runs(loop), "the ended one stays while the next runs");
        loop.feed(second, tantalum.value.event.ContextAssembledFixture.of(loop.onlyCommandId(), "system", List.of(), CompactStepsTest.SETTINGS,
                List.of()));
        loop.feed(second, CompactStepsTest.endTurn(loop, "a2", 200));
        assertEquals(Set.of(second), runs(loop), "the earlier ended run leaves when the next one ends");
        assertEquals(second, loop.session().lastRun());
    }

    /** 被去掉的运行的事件迟到（一次回复、一次取消）：内核本来就不收，什么都不改。 */
    @Test
    void lateEventsForARemovedRunChangeNothing() {
        LoopFixture loop = CompactStepsTest.start();
        RunId first = loop.runId();
        CommandId call = loop.onlyCommandId();
        loop.feed(first, CompactStepsTest.endTurn(loop, "a1", 100));
        loop.prompt("q2");
        RunId second = loop.runId();
        loop.feed(second, tantalum.value.event.ContextAssembledFixture.of(loop.onlyCommandId(), "system", List.of(), CompactStepsTest.SETTINGS,
                List.of()));
        loop.feed(second, CompactStepsTest.endTurn(loop, "a2", 200));
        assertTrue(!runs(loop).contains(first));

        KernelState before = loop.state;
        loop.feed(first, new ModelReplied(call, LoopFixture.text("late"), StopReason.END_TURN, "m", new TokenUsage(1, 1, 0, 0), null, null, null));
        assertSame(before, loop.state);
        loop.feed(first, new Cancel(new RequestId("c")));
        assertSame(before, loop.state);
    }
}
