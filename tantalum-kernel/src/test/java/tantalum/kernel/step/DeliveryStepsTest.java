package tantalum.kernel.step;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.kernel.LoopFixture;
import tantalum.value.assignment.AssignmentOutcome;
import tantalum.value.command.Command;
import tantalum.value.command.Deliver;
import tantalum.value.command.StartTimer;
import tantalum.value.decision.Decision;
import tantalum.value.delivery.Outgoing;
import tantalum.value.event.CommandDropped;
import tantalum.value.event.ContextAssembledFixture;
import tantalum.value.event.Delivered;
import tantalum.value.event.ModelReplied;
import tantalum.value.event.Redeliver;
import tantalum.value.event.Refused;
import tantalum.value.event.TimerFired;
import tantalum.value.model.ReportBlock;
import tantalum.value.model.StopReason;

/** delivery 需求 1、2：信箱、确认、到时重送、定时器被丢也重送、启动重送、不收的交代记成失败、排在后面的叫停一起划掉。 */
class DeliveryStepsTest {

    private static Decision callTask(LoopFixture loop, String id) {
        loop.prompt("go");
        loop.feed(loop.runId(), ContextAssembledFixture.of(loop.onlyCommandId(), "s", List.of(), null, List.of()));
        return loop.feed(loop.runId(), new ModelReplied(loop.onlyCommandId(), List.of(AssignmentFixture.task(id, "Check module A.")),
                StopReason.TOOL_USE, "m", null, null, null, null));
    }

    private static Outgoing only(LoopFixture loop) {
        assertEquals(1, loop.session().outbox().size(), loop.session().outbox().toString());
        return loop.session().outbox().getFirst();
    }

    private static List<Deliver> delivers(Decision decision) {
        return decision.commands().stream().filter(Deliver.class::isInstance).map(Deliver.class::cast).toList();
    }

    @Test
    void aTaskWaitsInTheOutboxUntilItIsConfirmed() {
        LoopFixture loop = new LoopFixture();
        Decision started = callTask(loop, "c1");
        Outgoing sent = only(loop);
        assertEquals(1, delivers(started).size());
        assertTrue(started.commands().stream().anyMatch(c -> c instanceof StartTimer t && t.id().equals(sent.ackTimer()) && t.delay().equals(Outbox.ACK_AFTER)));

        Decision confirmed = loop.feed(null, new Delivered(sent.id()));
        assertTrue(loop.session().outbox().isEmpty());
        assertTrue(confirmed.commands().stream().anyMatch(c -> c instanceof tantalum.value.command.AbortCommands abort
                && abort.commands().contains(sent.ackTimer())), "the ack timer is stopped");
        assertSame(loop.state, loop.feed(null, new Delivered(sent.id())).newState(), "a second confirmation changes nothing");
    }

    @Test
    void anUnconfirmedTaskIsSentAgainWhenItsTimerFiresOrIsDropped() {
        LoopFixture loop = new LoopFixture();
        callTask(loop, "c1");
        Outgoing sent = only(loop);

        Decision again = loop.feed(null, new TimerFired(sent.ackTimer()));
        assertEquals(sent.what(), delivers(again).getFirst().what(), "the same delivery again");
        Outgoing resent = only(loop);
        assertTrue(!resent.ackTimer().equals(sent.ackTimer()), "a new timer");

        Decision afterDrop = loop.feed(null, new CommandDropped(resent.ackTimer(), "output ring full"));
        assertEquals(1, delivers(afterDrop).size(), "a dropped ack timer resends too, the outbox does not get stuck");

        Decision restarted = loop.feed(null, new Redeliver());
        assertEquals(1, delivers(restarted).size(), "after a restart the head is sent again");
        assertSame(loop.state, loop.feed(null, new TimerFired(sent.ackTimer())).newState(), "an old timer does nothing");
    }

    @Test
    void aRefusedTaskIsAFailedResultAndTheStopQueuedBehindItGoes() {
        LoopFixture loop = new LoopFixture();
        callTask(loop, "c1");
        String id = loop.session().assignments().open().getFirst().id();
        loop.feed(null, new tantalum.value.event.StopRequested(new tantalum.value.id.RequestId("r"), null));
        assertEquals(2, loop.session().outbox().size(), "the stop waits behind its task");

        loop.feed(null, new Refused("assign:" + id, "unknown agent \"x\""));
        assertTrue(loop.session().outbox().isEmpty(), "the queued stop of the refused task goes with it");
        assertTrue(loop.session().assignments().open().isEmpty());
        boolean shown = false;
        // 会话正忙：结果在收件队列里，下次调模型前并进历史
        List<tantalum.value.model.Message> seen = new java.util.ArrayList<>(loop.session().messages());
        loop.session().inbox().forEach(item -> seen.add(item.message()));
        for (tantalum.value.model.Message message : seen) {
            for (tantalum.value.model.ContentBlock block : message.content()) {
                if (block instanceof ReportBlock report && report.assignment().equals(id)) {
                    assertEquals(AssignmentOutcome.FAILED, report.outcome());
                    assertTrue(report.text().contains("unknown agent"), report.text());
                    shown = true;
                }
            }
        }
        assertTrue(shown, "the refusal is the task's result");
    }

    @Test
    void theNextDeliveryToTheSameSessionWaitsForTheFirst() {
        LoopFixture loop = new LoopFixture();
        callTask(loop, "c1");
        String id = loop.session().assignments().open().getFirst().id();
        Decision stopped = loop.feed(null, new tantalum.value.event.StopRequested(new tantalum.value.id.RequestId("r"), null));
        assertTrue(delivers(stopped).isEmpty(), "nothing goes out while the task is unconfirmed");

        Decision confirmed = loop.feed(null, new Delivered("assign:" + id));
        List<Deliver> next = delivers(confirmed);
        assertEquals(1, next.size());
        assertEquals("stop:" + id, next.getFirst().what().id(), "the stop goes once the task has arrived");
    }

    /** 收的一方：重复的交代只回“送到了”；不是给它的不收。 */
    @Test
    void theReceiverConfirmsRepeatsAndRefusesWhatIsNotItsOwn() {
        LoopFixture loop = new LoopFixture();
        Decision started = callTask(loop, "c1");
        AssignmentViews.Assign start = (AssignmentViews.Assign) AssignmentFixture.view(delivers(started).getFirst());
        Decision arrived = AssignmentFixture.deliver(loop, start);
        assertTrue(arrived.commands().stream().anyMatch(c -> c instanceof tantalum.value.command.Acknowledge ack && ack.refused() == null));
        assertTrue(loop.session().outbox().isEmpty(), "the fixture sent the confirmation back");

        Decision repeat = loop.feed(start.session(), null, AssignmentFixture.assignmentOf(start));
        List<Command> commands = repeat.commands();
        assertEquals(1, commands.size());
        assertTrue(commands.getFirst() instanceof tantalum.value.command.Acknowledge ack && ack.refused() == null, commands.toString());

        Decision other = loop.feed(start.session(), null, new tantalum.value.event.Assignment("t9-9", LoopFixture.SESSION,
                tantalum.value.event.Root.USER, "something else", null));
        assertTrue(other.commands().getFirst() instanceof tantalum.value.command.Acknowledge ack && ack.refused() != null, other.commands().toString());
    }
}
