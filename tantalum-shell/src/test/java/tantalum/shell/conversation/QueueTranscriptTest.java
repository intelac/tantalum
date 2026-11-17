package tantalum.shell.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tantalum.shell.conversation.TranscriptFixture.text;

import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.shell.conversation.TranscriptItem.UserMessage;
import tantalum.value.event.FollowUpSubmitted;
import tantalum.value.event.QueueChanged;
import tantalum.value.event.QueueChanged.Action;
import tantalum.value.id.RequestId;
import tantalum.value.id.RunId;
import tantalum.value.id.SubmissionId;
import tantalum.value.event.FollowUpSubmittedFixture;

/** input-area 需求 1.3、1.5、1.6、1.11：排队的提问改了以后，对话条目跟着变，条目 ID 不变。 */
class QueueTranscriptTest {

    private final TranscriptFixture fx = new TranscriptFixture();
    private int next;

    private RunId queued(String... texts) {
        RunId run = fx.start("first");
        for (String text : texts) {
            fx.feed(null, FollowUpSubmittedFixture.of(new SubmissionId("f" + text), new RequestId("rf" + text), text));
        }
        return run;
    }

    private void change(Action action, int index, String expected, String text) {
        int n = next++;
        fx.feed(null, new QueueChanged(new SubmissionId("q" + n), new RequestId("qr" + n), action, index, expected, List.of(), text));
    }

    private UserMessage message(int position) {
        return fx.items(UserMessage.class).get(position);
    }

    @Test
    void editChangesTheTextInPlace() {
        queued("a", "b");
        String id = message(2).id();
        change(Action.EDIT, 1, "b", "b2");

        assertEquals("b2", message(2).text());
        assertEquals(id, message(2).id());
        assertTrue(message(2).queued());
        assertEquals("a", message(1).text());
    }

    @Test
    void aRemovedMessageStaysAsATombstoneAndTheNextOneStartsInItsPlace() {
        RunId first = queued("a", "b");
        change(Action.REMOVE, 0, "a", null);

        UserMessage removed = message(1);
        assertTrue(removed.removed());
        assertFalse(removed.queued());
        assertNull(removed.run());
        assertTrue(message(2).queued());

        fx.feed(first, fx.reply(text("done")));
        assertEquals(RunId.of(TranscriptFixture.SESSION, fx.lastSeq()), message(2).run(), "b starts, not the removed a");
        assertNull(message(1).run());
    }

    @Test
    void aSteeredMessageJoinsTheRunningRun() {
        RunId first = queued("a", "b");
        change(Action.STEER, 1, "b", null);

        assertEquals(first, message(2).run());
        assertFalse(message(2).queued());
        assertFalse(message(2).removed());
        assertTrue(message(1).queued());

        // 有插话没读，运行不结束，再调一次模型
        fx.feed(first, fx.reply(text("done")));
        assertTrue(message(1).queued());
        fx.feed(first, fx.reply(text("done again")));
        assertEquals(RunId.of(TranscriptFixture.SESSION, fx.lastSeq()), message(1).run());
        assertEquals(first, message(2).run());
    }

    @Test
    void aChangeThatFoundNothingLeavesTheItemsAlone() {
        queued("a", "b");
        List<TranscriptItem> before = fx.items();
        change(Action.REMOVE, 1, "gone", null);
        change(Action.EDIT, 0, "gone", "x");
        change(Action.STEER, 0, "gone", null);
        assertEquals(before, fx.items());
    }
}
