package tantalum.kernel.step;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static tantalum.kernel.LoopFixture.text;

import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.kernel.LoopFixture;
import tantalum.value.command.AssembleContext;
import tantalum.value.event.ContextAssembled;
import tantalum.value.event.FollowUpSubmitted;
import tantalum.value.event.ModelReplied;
import tantalum.value.id.RequestId;
import tantalum.value.id.RunId;
import tantalum.value.id.SubmissionId;
import tantalum.value.model.Message;
import tantalum.value.model.StopReason;

class FollowUpTest {

    private static FollowUpSubmitted followUp(String text) {
        return new FollowUpSubmitted(new SubmissionId("f" + text), new RequestId("r" + text), text);
    }

    private static void finish(LoopFixture loop) {
        loop.feed(loop.runId(), new ContextAssembled(loop.onlyCommandId(), "s", List.of(), null, List.of()));
        loop.feed(loop.runId(), new ModelReplied(loop.onlyCommandId(), text("x"), StopReason.END_TURN, "m", null));
    }

    @Test
    void endedRunTakesOneQueuedFollowUp() {
        LoopFixture loop = new LoopFixture();
        loop.prompt("first");
        RunId first = loop.runId();
        var pending = loop.onlyCommandId();
        loop.feed(null, followUp("a"));
        loop.feed(null, followUp("b"));
        assertEquals(first, loop.runId());
        assertEquals(List.of("a", "b"), loop.session().followUps());

        loop.feed(first, new ContextAssembled(pending, "s", List.of(), null, List.of()));
        loop.feed(first, new ModelReplied(loop.onlyCommandId(), text("x"), StopReason.END_TURN, "m", null));

        assertNotEquals(first, loop.runId());
        assertEquals("AwaitingContext", loop.phase());
        assertInstanceOf(AssembleContext.class, loop.onlyCommand());
        assertEquals(List.of("b"), loop.session().followUps());
        assertEquals(Message.user("a"), loop.session().messages().getLast());
    }

    @Test
    void followUpOnAnIdleSessionStartsARun() {
        LoopFixture loop = new LoopFixture();
        loop.prompt("first");
        finish(loop);
        loop.feed(null, followUp("next"));
        assertEquals("AwaitingContext", loop.phase());
    }
}
