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

    /** 压缩后的视图、分隔条目和截断标记都从 journal 重算出来，和实时逐字节相同。 */
    @Test
    void replayOfACompactedConversationMatches() {
        tantalum.value.state.CompactionSettings settings = new tantalum.value.state.CompactionSettings(1000, 200, 70, 60, 30, 500);
        String q = ("q ").repeat(45);
        TranscriptFixture live = new TranscriptFixture(true, List.of());
        RunId first = live.start(q, settings);
        live.feed(first, live.reply(new tantalum.value.model.TokenUsage(700, 10, 0, 0), text(("a ").repeat(45))));
        live.feed(null, live.prompt(q));
        RunId second = new RunId("run-" + live.lastSeq());
        live.feed(second, new tantalum.value.event.ContextAssembled(live.lastCommands().getFirst().id(), "sys", List.of(), null, settings));
        live.feed(second, live.reply(new tantalum.value.model.TokenUsage(790, 10, 0, 0), text(("b ").repeat(45))));
        live.feed(null, live.prompt(q));
        RunId third = new RunId("run-" + live.lastSeq());
        live.feed(third, new tantalum.value.event.ContextAssembled(live.lastCommands().getFirst().id(), "sys", List.of(), null, settings));
        live.feed(third, live.reply(new tantalum.value.model.TokenUsage(50, 20, 0, 0), text("Goal: q")));
        live.feed(third, new tantalum.value.event.ContextAssembled(live.lastCommands().getFirst().id(), "sys", List.of(), null, settings));
        live.feed(third, live.truncatedReply("a3 cut"));

        Transcript transcript = new Transcript();
        KernelState state = transcript.replay(freshKernel(true), live.envelopes);

        assertEquals(live.items(), transcript.items(SESSION));
        assertEquals(live.state(), state);
        assertEquals(1, transcript.items(SESSION).stream().filter(TranscriptItem.CompactionMark.class::isInstance).count());
        assertEquals(3, state.sessions().get(SESSION).compaction().replacement().size(), "summary, kept a2, kept q3");
    }

    @Test
    void outOfOrderEventsAreRejected() {
        TranscriptFixture live = new TranscriptFixture();
        live.start("hi");
        List<EventEnvelope> reversed = live.envelopes.reversed();

        assertThrows(IllegalArgumentException.class, () -> new Transcript().replay(freshKernel(true), reversed));
    }
}
