package tantalum.shell.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static tantalum.shell.conversation.TranscriptFixture.SESSION;
import static tantalum.shell.conversation.TranscriptFixture.badCall;
import static tantalum.shell.conversation.TranscriptFixture.call;
import static tantalum.shell.conversation.TranscriptFixture.claudeThinking;
import static tantalum.shell.conversation.TranscriptFixture.text;

import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.kernel.Kernel;
import tantalum.kernel.hook.HookChain;
import tantalum.kernel.table.BuiltinSteps;
import tantalum.kernel.table.TransitionTable;
import tantalum.value.event.Cancel;
import tantalum.value.event.EventEnvelope;
import tantalum.value.event.FollowUpSubmitted;
import tantalum.value.id.RequestId;
import tantalum.value.id.RunId;
import tantalum.value.id.SubmissionId;
import tantalum.value.state.KernelState;

/** 回放得到的条目与实时相同：重启后对话区和工具链完整还原。 */
class TranscriptReplayTest {

    private static Kernel freshKernel(boolean concurrencySafe) {
        TransitionTable table = new TransitionTable();
        BuiltinSteps.register(table);
        return new Kernel(table, new HookChain(List.of()), (tool, args) -> concurrencySafe, 10);
    }

    @Test
    void replayingTheSameEventsGivesTheSameItems() {
        TranscriptFixture live = new TranscriptFixture(false, List.of());
        RunId run = live.start("hi");
        live.feed(run, live.reply(claudeThinking("plan"), call("c1", "write"), call("c2", "write"), badCall("c3", "write")));
        live.feed(run, live.finished(run, "c1", "ok", false));
        live.feed(null, new FollowUpSubmitted(new SubmissionId("f"), new RequestId("rf"), "later"));
        live.feed(run, new Cancel(new RequestId("x")));
        live.feed(null, live.prompt("third"));

        Transcript transcript = new Transcript();
        KernelState state = transcript.replay(freshKernel(false), live.envelopes);

        assertEquals(live.items(), transcript.items(SESSION));
        assertEquals(live.state(), state);
    }

    @Test
    void replayOfAFinishedConversationMatchesToo() {
        TranscriptFixture live = new TranscriptFixture(true, List.of());
        RunId run = live.start("hi");
        live.feed(run, live.reply(call("c1", "read_file"), call("c2", "grep")));
        live.feed(run, live.finished(run, "c2", "b", false));
        live.feed(run, live.finished(run, "c1", "a", true));
        live.feed(run, live.reply(claudeThinking("done thinking"), text("done")));

        Transcript transcript = new Transcript();
        transcript.replay(freshKernel(true), live.envelopes);

        assertEquals(live.items(), transcript.items(SESSION));
        assertEquals(6, transcript.items(SESSION).size());
    }

    @Test
    void outOfOrderEventsAreRejected() {
        TranscriptFixture live = new TranscriptFixture();
        live.start("hi");
        List<EventEnvelope> reversed = live.envelopes.reversed();

        assertThrows(IllegalArgumentException.class, () -> new Transcript().replay(freshKernel(true), reversed));
    }
}
