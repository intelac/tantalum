package tantalum.kernel.step;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static tantalum.kernel.step.CompactStepsTest.block;
import static tantalum.kernel.step.CompactStepsTest.endTurn;
import static tantalum.kernel.step.CompactStepsTest.reply;
import static tantalum.kernel.step.CompactStepsTest.thinkingAndCall;

import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.kernel.LoopFixture;
import tantalum.value.command.CallModel;
import tantalum.value.event.ContextAssembled;
import tantalum.value.event.ToolFinished;
import tantalum.value.model.Role;
import tantalum.value.model.StopReason;
import tantalum.value.model.TokenUsage;
import tantalum.value.state.CompactionSettings;

/**
 * compaction-threshold T6：每次提问组装上下文时换上 shell 按用户当前选择算出的压缩设置。窗口 1000、预留 200，
 * 用满是 800；第一次提问后的实测用量 400。
 */
class CompactionSwitchTest {

    private static CompactionSettings point(long point) {
        return new CompactionSettings(1000, 200, 70, 60, 30, 500, point, 3);
    }

    private static final CompactionSettings LEGACY = new CompactionSettings(1000, 200, 70, 60, 30, 500);

    private static void assemble(LoopFixture loop, CompactionSettings settings) {
        loop.feed(loop.runId(), new ContextAssembled(loop.onlyCommandId(), "system", List.of(), settings, List.of()));
    }

    /** 第一次提问：组装、回复（实测 400），运行结束；然后第二次提问。 */
    private static LoopFixture secondQuestion(CompactionSettings first) {
        LoopFixture loop = new LoopFixture();
        loop.prompt(block("q1"));
        assemble(loop, first);
        loop.feed(loop.runId(), endTurn(loop, block("a1"), 390));
        loop.prompt(block("q2"));
        return loop;
    }

    @Test
    void theNextQuestionUsesTheNewSettings() {
        LoopFixture loop = secondQuestion(point(800));
        assemble(loop, point(600));
        assertEquals(point(600), loop.session().context().compaction());
        assertEquals("AwaitingModel", loop.phase(), "400 + 30 is still below 600");
    }

    @Test
    void changingThePointTellsTheModelNothing() {
        LoopFixture loop = secondQuestion(point(800));
        CallModel before = assertInstanceOf(CallModel.class, loop.commands.stream().filter(CallModel.class::isInstance).findFirst().orElseThrow());
        assemble(loop, point(600));
        CallModel after = assertInstanceOf(CallModel.class, loop.onlyCommand());
        assertEquals(before.prompt().system(), after.prompt().system());
        assertEquals(before.prompt().tools(), after.prompt().tools());
        assertEquals(3, after.prompt().messages().size(), "q1, a1, q2 and nothing else");
        assertEquals(List.of(Role.USER, Role.ASSISTANT, Role.USER), after.prompt().messages().stream().map(m -> m.role()).toList());
    }

    @Test
    void aPointBelowTheCurrentSizeCompactsBeforeTheNextCall() {
        LoopFixture loop = secondQuestion(point(800));
        assemble(loop, point(300));
        assertEquals("AwaitingSummary", loop.phase(), "400 + 30 is above the new point 300");
    }

    @Test
    void oldEventsKeepTheFirstSettings() {
        LoopFixture loop = secondQuestion(LEGACY);
        assemble(loop, new CompactionSettings(1000, 100, 70, 60, 30, 500));
        assertEquals(LEGACY, loop.session().context().compaction(), "before compaction-threshold the first settings stayed");
    }

    @Test
    void anOldSessionTakesNewSettingsOnceTheyArrive() {
        LoopFixture loop = secondQuestion(LEGACY);
        assemble(loop, point(600));
        assertEquals(point(600), loop.session().context().compaction());
    }

    @Test
    void anUnknownWindowKeepsTheSettingsTheSessionHad() {
        LoopFixture loop = secondQuestion(point(800));
        assemble(loop, null);
        assertEquals(point(800), loop.session().context().compaction());
    }

    /** 同一个运行里的第二次调用不重新组装，所以用的还是开始时那份。 */
    @Test
    void aRunKeepsItsSettingsUntilItEnds() {
        LoopFixture loop = new LoopFixture();
        loop.prompt(block("q1"));
        assemble(loop, point(800));
        loop.feed(loop.runId(), reply(loop, thinkingAndCall("t1"), StopReason.TOOL_USE, new TokenUsage(100, 10, 0, 0)));
        loop.feed(loop.runId(), new ToolFinished(loop.onlyCommandId(), "t1", block("r1"), false, null));
        assertEquals("AwaitingModel", loop.phase());
        assertEquals(point(800), loop.session().context().compaction());
        assertNull(loop.run().compacting());
    }
}
