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
import tantalum.shell.conversation.TranscriptItem.CompactionMark;
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

    /** 窗口 1000、预留 200、保留 70：阈值 800。 */
    private static final tantalum.value.state.CompactionSettings SETTINGS =
            new tantalum.value.state.CompactionSettings(1000, 200, 70, 60, 30, 500);

    private static String block(String tag) {
        return (tag + " ").repeat(45).substring(0, 90);
    }

    /** 两轮问答后实测 790，第三个问题让大小越过阈值，第三个运行先发摘要调用：头部 q1、a1、q2，保留 a2、q3。 */
    private RunId runNeedingCompaction() {
        RunId first = fx.start(block("q1"), SETTINGS);
        fx.feed(first, fx.reply(new tantalum.value.model.TokenUsage(700, 10, 0, 0), text(block("a1"))));
        RunId second = nextRun(block("q2"));
        fx.feed(second, fx.reply(new tantalum.value.model.TokenUsage(790, 10, 0, 0), text(block("a2"))));
        return nextRun(block("q3"));
    }

    private RunId nextRun(String text) {
        fx.feed(null, fx.prompt(text));
        RunId run = RunId.of(TranscriptFixture.SESSION, fx.lastSeq());
        fx.feed(run, new tantalum.value.event.ContextAssembled(fx.lastCommands().getFirst().id(), "sys", List.of(), SETTINGS, List.of()));
        return run;
    }

    @Test
    void summaryReplyBecomesACompactionMarkNotAReply() {
        RunId run = runNeedingCompaction();
        fx.feed(run, fx.reply(new tantalum.value.model.TokenUsage(50, 20, 0, 0), claudeThinking("summarizing"), text("Goal: q")));

        List<CompactionMark> marks = fx.items(CompactionMark.class);
        assertEquals(1, marks.size());
        CompactionMark mark = marks.getFirst();
        assertEquals(run, mark.run());
        assertEquals(tantalum.value.state.Compacting.Trigger.AUTO, mark.trigger());
        assertEquals("Goal: q", mark.summary());
        assertFalse(mark.failed());
        assertTrue(mark.estimateAfter() < mark.estimateBefore());
        assertEquals(new tantalum.value.model.TokenUsage(50, 20, 0, 0), mark.usage());
        assertTrue(fx.items(Text.class).stream().noneMatch(item -> item.text().contains("Goal")), "the summary is not a reply");
        assertTrue(fx.items(Thinking.class).stream().noneMatch(item -> item.text().equals("summarizing")));
        assertEquals("AwaitingContext", fx.status(run).phase(), "the context is reassembled after compaction");
        fx.feed(run, new tantalum.value.event.ContextAssembled(fx.lastCommands().getFirst().id(), "sys", List.of(), SETTINGS, List.of()));

        fx.feed(run, fx.reply(text("a3")));
        assertEquals("a3", fx.items(Text.class).getLast().text());
        assertTrue(fx.status(run).isEnded());
        // 原始条目都还在：三条用户消息、三条回复文本。
        assertEquals(3, fx.items(UserMessage.class).size());
        assertEquals(3, fx.items(Text.class).size());
    }

    @Test
    void failedSummaryIsAMarkWithTheReasonAndNotARunFailure() {
        RunId run = runNeedingCompaction();
        fx.feed(run, fx.truncatedReply("half"));
        CompactionMark mark = fx.items(CompactionMark.class).getFirst();
        assertTrue(mark.failed());
        assertEquals("summary did not end normally: MAX_TOKENS", mark.failure());
        assertNull(mark.summary());
        assertNull(fx.status(run).failure(), "the run goes on; the summary failure is not the run's failure");
        assertEquals("AwaitingModel", fx.status(run).phase());

        fx.feed(run, fx.reply(new tantalum.value.model.TokenUsage(790, 10, 0, 0), text(block("a3"))));
        assertTrue(fx.status(run).isEnded());
        RunId again = nextRun(block("q4"));
        fx.feed(again, new ModelFailed(fx.callModel(), FailureReason.RATE_LIMITED, true, null, "", "429", null, null, null));
        assertNull(fx.status(again).failure(), "waiting to retry the summary is not a run failure");
        assertEquals("AwaitingSummaryRetry", fx.status(again).phase());
        assertEquals(1, fx.items(CompactionMark.class).size(), "no new mark while retrying");
        fx.feed(again, new tantalum.value.event.TimerFired(fx.lastCommands().getFirst().id()));
        fx.feed(again, new ModelFailed(fx.callModel(), FailureReason.INVALID_REQUEST, false, null, "", "400", null, null, null));
        assertTrue(fx.items(CompactionMark.class).getLast().failure().contains("400"));
        assertNull(fx.status(again).failure());
    }

    @Test
    void truncatedReplyIsMarked() {
        RunId run = fx.start("hi");
        fx.feed(run, fx.truncatedReply("half of the"));
        Text item = fx.items(Text.class).getFirst();
        assertTrue(item.truncated());
        assertFalse(item.interrupted());
        assertEquals(EndReason.COMPLETED, fx.status(run).endReason());
        assertEquals("reply truncated", fx.status(run).detail());
    }

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
            case CompactionMark c -> "compaction";
            case TranscriptItem.ToolChange c -> "tools";
            case TranscriptItem.ModelChange c -> "model";
            case TranscriptItem.Question q -> "question";
        }).toList();
        assertEquals(List.of("user:hi", "run", "think:plan", "tool:read_file", "think:check", "tool:grep",
                "think:wrap up", "text:done"), kinds);
        assertTrue(fx.status(run).isEnded());
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
        assertEquals(new Transcript.View(-1, List.of()), fx.transcript.view(SESSION));
        RunId run = fx.start("hi");
        fx.feed(run, fx.reply(text("a"), call("c1", "read_file")));

        Transcript.View snapshot = fx.transcript.view(SESSION);
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
        fx.feed(run, new ModelInterrupted(fx.decisions.get(1).commands().getFirst().id(), "partial an", null, null, null));

        Text partial = fx.items(Text.class).getFirst();
        assertTrue(partial.interrupted());
        assertEquals("partial an", partial.text());
        assertEquals(EndReason.CANCELLED, fx.status(run).endReason());
    }

    @Test
    void emptyInterruptionAddsNothing() {
        RunId run = fx.start("hi");
        fx.feed(run, new Cancel(new RequestId("x")));
        fx.feed(run, new ModelInterrupted(fx.decisions.get(1).commands().getFirst().id(), "", null, null, null));

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
        assertEquals(RunId.of(TranscriptFixture.SESSION, fx.lastSeq()), started.run());
        assertEquals(queued.id(), started.id());
        assertEquals(2, fx.items(RunStatus.class).size());
        assertEquals("AwaitingContext", fx.status(started.run()).phase());
    }

    @Test
    void runStatusCarriesPhaseElapsedAndFailure() {
        RunId run = fx.start("hi");
        assertEquals("AwaitingModel", fx.status(run).phase());

        fx.tick(Duration.ofSeconds(30));
        fx.feed(run, new ModelFailed(fx.callModel(), FailureReason.REFUSAL, false, null, "", "refused", null, null, null));

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
