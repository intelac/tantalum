package tantalum.shell.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tantalum.shell.conversation.TranscriptFixture.SESSION;
import static tantalum.shell.conversation.TranscriptFixture.T0;
import static tantalum.shell.conversation.TranscriptFixture.call;
import static tantalum.shell.conversation.TranscriptFixture.claudeThinking;
import static tantalum.shell.conversation.TranscriptFixture.openAiThinking;
import static tantalum.shell.conversation.TranscriptFixture.redacted;
import static tantalum.shell.conversation.TranscriptFixture.text;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.shell.conversation.TranscriptItem.RunStatus;
import tantalum.shell.conversation.TranscriptItem.Text;
import tantalum.shell.conversation.TranscriptItem.Thinking;
import tantalum.shell.conversation.TranscriptItem.ToolStep;
import tantalum.shell.conversation.TranscriptItem.UserMessage;
import tantalum.value.event.Cancel;
import tantalum.value.event.FollowUpSubmitted;
import tantalum.value.event.ModelFailed;
import tantalum.value.event.ModelInterrupted;
import tantalum.value.event.SteerSubmitted;
import tantalum.value.id.RequestId;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;
import tantalum.value.id.SubmissionId;
import tantalum.value.model.FailureReason;
import tantalum.value.state.EndReason;

/** 消息、思考和回复文本的顺序。工具链的细节在 {@link TranscriptToolTest}。 */
class TranscriptTest {

    private final TranscriptFixture fx = new TranscriptFixture();

    @Test
    void thinkingBeforeAndAfterEachToolCallIsKeptInOrder() {
        RunId run = fx.start("hi");
        fx.feed(run, fx.reply(claudeThinking("plan"), call("c1", "read_file")));
        fx.feed(run, fx.finished(run, "c1", "content", false));
        fx.feed(run, fx.reply(claudeThinking("check"), call("c2", "grep")));
        fx.feed(run, fx.finished(run, "c2", "matches", false));
        fx.feed(run, fx.reply(claudeThinking("wrap up"), text("done")));

        List<String> kinds = fx.items().stream().map(item -> switch (item) {
            case UserMessage u -> "user:" + u.text();
            case RunStatus s -> "run";
            case Thinking t -> "think:" + t.text();
            case ToolStep s -> "tool:" + s.call().name();
            case Text t -> "text:" + t.text();
        }).toList();
        assertEquals(List.of("user:hi", "run", "think:plan", "tool:read_file", "think:check", "tool:grep",
                "think:wrap up", "text:done"), kinds);
        assertTrue(fx.status(run).ended());
        assertEquals(EndReason.COMPLETED, fx.status(run).endReason());
    }

    @Test
    void idsComeFromSeqAndBlockIndex() {
        RunId run = fx.start("hi");
        fx.feed(run, fx.reply(claudeThinking("plan"), text("a"), call("c1", "read_file")));
        long replySeq = fx.lastSeq();

        assertEquals(replySeq + ".0", fx.items(Thinking.class).getFirst().id());
        assertEquals(replySeq + ".1", fx.items(Text.class).getFirst().id());
        assertEquals(replySeq + ".2", fx.items(ToolStep.class).getFirst().id());
        assertEquals("0.0", fx.items(UserMessage.class).getFirst().id());
        assertEquals("run:" + run.value(), fx.status(run).id());
    }

    @Test
    void snapshotCarriesTheLastAppliedSeqSoClientsCanMergeItWithSse() {
        assertEquals(new Transcript.Snapshot(-1, List.of()), fx.transcript.snapshot(SESSION));
        RunId run = fx.start("hi");
        fx.feed(run, fx.reply(text("a"), call("c1", "read_file")));

        Transcript.Snapshot snapshot = fx.transcript.snapshot(SESSION);
        assertEquals(fx.lastSeq(), snapshot.seq());
        assertEquals(fx.items(), snapshot.items());
    }

    @Test
    void thinkingTextIsExtractedPerProviderAndRedactedHasNoText() {
        RunId run = fx.start("hi");
        fx.feed(run, fx.reply(claudeThinking("claude"), openAiThinking("openai"), redacted(), text("ok")));

        List<Thinking> thoughts = fx.items(Thinking.class);
        assertEquals(List.of("claude", "openai", ""), thoughts.stream().map(Thinking::text).toList());
        assertFalse(thoughts.get(0).redacted());
        assertFalse(thoughts.get(1).redacted());
        assertTrue(thoughts.get(2).redacted());
    }

    @Test
    void interruptedReplyIsMarked() {
        RunId run = fx.start("hi");
        fx.feed(run, new Cancel(new RequestId("x")));
        fx.feed(run, new ModelInterrupted(fx.decisions.get(1).commands().getFirst().id(), "partial an"));

        Text partial = fx.items(Text.class).getFirst();
        assertTrue(partial.interrupted());
        assertEquals("partial an", partial.text());
        assertEquals(EndReason.CANCELLED, fx.status(run).endReason());
    }

    @Test
    void emptyInterruptionAddsNothing() {
        RunId run = fx.start("hi");
        fx.feed(run, new Cancel(new RequestId("x")));
        fx.feed(run, new ModelInterrupted(fx.decisions.get(1).commands().getFirst().id(), ""));

        assertTrue(fx.items(Text.class).isEmpty());
    }

    @Test
    void steerDuringRunBelongsToThatRunAndIsNotQueued() {
        RunId run = fx.start("hi");
        fx.feed(null, new SteerSubmitted(new SubmissionId("st"), new RequestId("rs"), "also this"));

        UserMessage steer = fx.items(UserMessage.class).get(1);
        assertEquals("also this", steer.text());
        assertEquals(run, steer.run());
        assertFalse(steer.queued());
    }

    @Test
    void followUpIsQueuedUntilItsOwnRunStarts() {
        RunId first = fx.start("hi");
        fx.feed(null, new FollowUpSubmitted(new SubmissionId("f"), new RequestId("rf"), "later"));

        UserMessage queued = fx.items(UserMessage.class).get(1);
        assertTrue(queued.queued());
        assertNull(queued.run());

        fx.feed(first, fx.reply(text("done")));

        UserMessage started = fx.items(UserMessage.class).get(1);
        assertFalse(started.queued());
        assertEquals(new RunId("run-" + fx.lastSeq()), started.run());
        assertEquals(queued.id(), started.id());
        assertEquals(2, fx.items(RunStatus.class).size());
        assertEquals("AwaitingContext", fx.status(started.run()).phase());
    }

    @Test
    void runStatusCarriesPhaseElapsedAndFailure() {
        RunId run = fx.start("hi");
        assertEquals("AwaitingModel", fx.status(run).phase());

        fx.tick(Duration.ofSeconds(30));
        fx.feed(run, new ModelFailed(fx.callModel(), FailureReason.REFUSAL, false, null, "", "refused"));

        RunStatus status = fx.status(run);
        assertEquals(FailureReason.REFUSAL, status.failure());
        assertEquals(EndReason.FAILED, status.endReason());
        assertEquals("refused", status.detail());
        assertEquals(T0, status.at());
        assertEquals(Duration.ofSeconds(32), status.elapsed());
    }

    @Test
    void listenerGetsEachChangedItemOncePerEventWithItsPosition() {
        RunId run = fx.start("hi");
        fx.changes.clear();
        fx.feed(run, fx.reply(claudeThinking("plan"), call("c1", "read_file")));

        assertEquals(List.of(fx.lastSeq() + ".0", fx.lastSeq() + ".1", "run:" + run.value()),
                fx.changes.stream().map(change -> change.item().id()).toList());
        assertEquals(List.of(0, 1, 2), fx.changes.stream().map(TranscriptFixture.Change::index).toList());
        assertTrue(fx.changes.stream().allMatch(change -> change.seq() == fx.lastSeq()));
        assertEquals(TranscriptItem.StepStatus.RUNNING, ((ToolStep) fx.changes.get(1).item()).status());
    }

    @Test
    void unknownSessionIsEmptyAndSessionsAreListed() {
        assertTrue(fx.transcript.items(new SessionId("nope")).isEmpty());
        fx.start("hi");
        assertEquals(List.of(SESSION), List.copyOf(fx.transcript.sessions()));
    }
}
