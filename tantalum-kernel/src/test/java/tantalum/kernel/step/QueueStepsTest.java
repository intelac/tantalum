package tantalum.kernel.step;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.kernel.LoopFixture;
import tantalum.value.decision.Decision;
import tantalum.value.decision.QueueEdited;
import tantalum.value.event.ContextAssembled;
import tantalum.value.event.FollowUpSubmitted;
import tantalum.value.event.QueueChanged;
import tantalum.value.event.QueueChanged.Action;
import tantalum.value.id.RequestId;
import tantalum.value.id.SubmissionId;
import tantalum.value.model.Message;
import tantalum.value.model.ModelChoice;
import tantalum.value.state.KernelState;

/** input-area 需求 1.3、1.5、1.6、1.11：改排队的提问。 */
class QueueStepsTest {

    private static final ModelChoice QWEN = new ModelChoice("local", "qwen3.8", "on");
    private int next;

    private QueueChanged change(Action action, int index, String expected, String text) {
        int n = next++;
        return new QueueChanged(new SubmissionId("q" + n), new RequestId("qr" + n), action, index, expected, List.of(), text);
    }

    /** 一个在等上下文的运行，后面排着 a、b（b 带模型选择）、c。 */
    private static LoopFixture queued() {
        LoopFixture loop = new LoopFixture();
        loop.prompt("first");
        loop.feed(null, new FollowUpSubmitted(new SubmissionId("fa"), new RequestId("ra"), "a"));
        loop.feed(null, new FollowUpSubmitted(new SubmissionId("fb"), new RequestId("rb"), "b", List.of(), QWEN));
        loop.feed(null, new FollowUpSubmitted(new SubmissionId("fc"), new RequestId("rc"), "c"));
        return loop;
    }

    private static List<QueueEdited> edited(Decision decision) {
        return decision.facts().stream().filter(QueueEdited.class::isInstance).map(QueueEdited.class::cast).toList();
    }

    @Test
    void editReplacesTheTextAndKeepsTheModelChoice() {
        LoopFixture loop = queued();
        Decision decision = loop.feed(null, change(Action.EDIT, 1, "b", "b2"));
        assertEquals(List.of(Message.user("a"), Message.user("b2"), Message.user("c")), loop.session().followUps());
        assertEquals(Arrays.asList(null, QWEN, null), loop.session().followUpModels());
        assertEquals(List.of(new QueueEdited(loop.run().ref(), Action.EDIT, 1)), edited(decision));
        assertTrue(decision.commands().isEmpty());
    }

    @Test
    void removeDropsTheMessageAndItsModelChoice() {
        LoopFixture loop = queued();
        Decision decision = loop.feed(null, change(Action.REMOVE, 1, "b", null));
        assertEquals(List.of(Message.user("a"), Message.user("c")), loop.session().followUps());
        assertEquals(Arrays.asList(null, null), loop.session().followUpModels());
        assertEquals(List.of(new QueueEdited(loop.run().ref(), Action.REMOVE, 1)), edited(decision));
    }

    @Test
    void steerMovesTheMessageIntoTheRunAndTheNextModelCallSeesIt() {
        LoopFixture loop = new LoopFixture();
        loop.prompt("first");
        var pending = loop.onlyCommandId();
        for (String text : List.of("a", "b", "c")) {
            loop.feed(null, new FollowUpSubmitted(new SubmissionId("f" + text), new RequestId("r" + text), text));
        }
        Decision decision = loop.feed(null, change(Action.STEER, 2, "c", null));
        assertEquals(List.of(Message.user("a"), Message.user("b")), loop.session().followUps());
        assertEquals(List.of(Message.user("c")), loop.run().pendingSteers());
        assertEquals(List.of(new QueueEdited(loop.run().ref(), Action.STEER, 2)), edited(decision));

        loop.feed(loop.runId(), new ContextAssembled(pending, "s", List.of(), null, List.of()));
        assertEquals("AwaitingModel", loop.phase());
        assertEquals(List.of(Message.user("first"), Message.user("c")), loop.session().messages());
        assertTrue(loop.run().pendingSteers().isEmpty());
    }

    @Test
    void aMessageThatMovedTowardsTheHeadIsStillFound() {
        LoopFixture loop = queued();
        loop.feed(null, change(Action.REMOVE, 0, "a", null));
        // 页面提交时 c 还在位置 2，现在在 1
        Decision decision = loop.feed(null, change(Action.EDIT, 2, "c", "c2"));
        assertEquals(List.of(Message.user("b"), Message.user("c2")), loop.session().followUps());
        assertEquals(1, edited(decision).getFirst().index());
    }

    @Test
    void aMessageThatIsGoneChangesNothing() {
        for (Action action : Action.values()) {
            LoopFixture loop = queued();
            KernelState before = loop.state;
            Decision decision = loop.feed(null, change(action, 1, "gone", action == Action.EDIT ? "x" : null));
            assertEquals(before, loop.state, action.name());
            assertEquals(List.of(new QueueEdited(loop.run().ref(), action, -1)), edited(decision), action.name());
        }
    }

    @Test
    void anIdleSessionHasNoQueueToChange() {
        for (Action action : Action.values()) {
            LoopFixture loop = new LoopFixture();
            KernelState before = loop.state;
            Decision decision = loop.feed(null, change(action, 0, "a", action == Action.EDIT ? "x" : null));
            assertEquals(before, loop.state, action.name());
            assertTrue(decision.facts().isEmpty(), action.name());
        }
    }
}
