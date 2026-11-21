package tantalum.kernel.step;

import tantalum.value.event.UserPromptFixture;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.kernel.LoopFixture;
import tantalum.value.decision.Decision;
import tantalum.value.decision.QueueEdited;
import tantalum.value.event.ContextAssembled;
import tantalum.value.event.QueueChanged;
import tantalum.value.event.QueueChanged.Action;
import tantalum.value.id.RequestId;
import tantalum.value.id.SubmissionId;
import tantalum.value.model.Message;
import tantalum.value.model.ModelChoice;
import tantalum.value.state.KernelState;
import tantalum.value.event.ContextAssembledFixture;

/** input-area 需求 1.3、1.5、1.6；session-model 需求 3.6：改收件队列里你自己排的一条，按编号找。 */
class QueueStepsTest {

    private static final ModelChoice QWEN = new ModelChoice("local", "qwen3.8", "on");

    private int next;

    private QueueChanged change(Action action, long item, String text) {
        int n = next++;
        return new QueueChanged(new SubmissionId("q" + n), new RequestId("qr" + n), action, item, text);
    }

    /** 一个在等上下文的运行，后面排着 a、b（b 带模型选择）、c。 */
    private static LoopFixture queued() {
        LoopFixture loop = new LoopFixture();
        loop.prompt("first");
        loop.feed(null, UserPromptFixture.of(new SubmissionId("fa"), new RequestId("ra"), "a"));
        loop.feed(null, UserPromptFixture.of(new SubmissionId("fb"), new RequestId("rb"), "b", List.of(), QWEN));
        loop.feed(null, UserPromptFixture.of(new SubmissionId("fc"), new RequestId("rc"), "c"));
        return loop;
    }

    private static List<QueueEdited> edited(Decision decision) {
        return decision.facts().stream().filter(QueueEdited.class::isInstance).map(QueueEdited.class::cast).toList();
    }

    @Test
    void editReplacesTheTextAndKeepsTheModelChoiceAndTheId() {
        LoopFixture loop = queued();
        long b = loop.item("b");
        Decision decision = loop.feed(null, change(Action.EDIT, b, "b2"));

        assertEquals(List.of(Message.user("a"), Message.user("b2"), Message.user("c")), loop.queued());
        assertEquals(Arrays.asList(null, QWEN, null), loop.queuedModels());
        assertEquals(b, loop.item("b2"), "the item keeps its id, so the page can change it again");
        assertEquals(List.of(new QueueEdited(loop.run().ref(), Action.EDIT, b)), edited(decision));
        assertTrue(decision.commands().isEmpty());
    }

    @Test
    void removeDropsTheMessageAndItsModelChoice() {
        LoopFixture loop = queued();
        long b = loop.item("b");
        Decision decision = loop.feed(null, change(Action.REMOVE, b, null));

        assertEquals(List.of(Message.user("a"), Message.user("c")), loop.queued());
        assertEquals(Arrays.asList(null, null), loop.queuedModels());
        assertEquals(List.of(new QueueEdited(loop.run().ref(), Action.REMOVE, b)), edited(decision));
    }

    @Test
    void joinMovesTheMessageIntoTheRunAndTheNextModelCallSeesIt() {
        LoopFixture loop = new LoopFixture();
        loop.prompt("first");
        var pending = loop.onlyCommandId();
        for (String text : List.of("a", "b", "c")) {
            loop.feed(null, UserPromptFixture.of(new SubmissionId("f" + text), new RequestId("r" + text), text));
        }
        long c = loop.item("c");
        Decision decision = loop.feed(null, change(Action.JOIN, c, null));

        assertEquals(List.of(Message.user("a"), Message.user("b")), loop.queued());
        assertEquals(List.of(Message.user("c")), loop.joining());
        assertEquals(List.of(new QueueEdited(loop.run().ref(), Action.JOIN, c)), edited(decision));

        loop.feed(loop.runId(), ContextAssembledFixture.of(pending, "s", List.of(), null, List.of()));
        assertEquals("AwaitingModel", loop.phase());
        assertEquals(List.of(Message.user("first"), Message.user("c")), loop.session().messages());
        assertTrue(loop.joining().isEmpty());
    }

    /** 需求 3.6：按编号找，它现在排在第几都一样（取代“按位置加原文往前找”）。 */
    @Test
    void anItemIsFoundByItsIdWhereverItStandsNow() {
        LoopFixture loop = queued();
        long c = loop.item("c");
        loop.feed(null, change(Action.REMOVE, loop.item("a"), null));
        Decision decision = loop.feed(null, change(Action.EDIT, c, "c2"));

        assertEquals(List.of(Message.user("b"), Message.user("c2")), loop.queued());
        assertEquals(c, edited(decision).getFirst().item());
    }

    @Test
    void anItemThatIsGoneChangesNothing() {
        for (Action action : Action.values()) {
            LoopFixture loop = queued();
            KernelState before = loop.state;
            Decision decision = loop.feed(null, change(action, 999, action == Action.EDIT ? "x" : null));
            assertEquals(before, loop.state, action.name());
            assertEquals(List.of(new QueueEdited(loop.run().ref(), action, -1)), edited(decision), action.name());
        }
    }

    /** 已经改成并进这一轮的那条不在“你排着的”里了，不能再改、再拿掉。 */
    @Test
    void anItemAlreadyJoiningTheRunCanNoLongerBeChanged() {
        LoopFixture loop = queued();
        long b = loop.item("b");
        loop.feed(null, change(Action.JOIN, b, null));
        KernelState before = loop.state;
        for (Action action : Action.values()) {
            Decision decision = loop.feed(null, change(action, b, action == Action.EDIT ? "x" : null));
            assertEquals(before, loop.state, action.name());
            assertEquals(-1, edited(decision).getFirst().item(), action.name());
        }
        assertEquals(List.of(Message.user("b")), loop.joining());
    }

    @Test
    void anIdleSessionHasNoQueueToChange() {
        for (Action action : Action.values()) {
            LoopFixture loop = new LoopFixture();
            KernelState before = loop.state;
            Decision decision = loop.feed(null, change(action, 1, action == Action.EDIT ? "x" : null));
            assertEquals(before, loop.state, action.name());
            assertTrue(decision.facts().isEmpty(), action.name());
        }
    }
}
