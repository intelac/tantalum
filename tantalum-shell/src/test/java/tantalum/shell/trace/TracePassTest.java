package tantalum.shell.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tantalum.shell.trace.TraceFixture.SETTINGS;
import static tantalum.shell.trace.TraceFixture.block;
import static tantalum.shell.trace.TraceFixture.call;
import static tantalum.shell.trace.TraceFixture.text;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tantalum.kernel.hook.KernelHook;
import tantalum.kernel.step.RetrySteps;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.trace.TraceRecord.Attempt;
import tantalum.shell.trace.TraceRecord.Compacted;
import tantalum.shell.trace.TraceRecord.ContextChange;
import tantalum.shell.trace.TraceRecord.HookDecision;
import tantalum.shell.trace.TraceRecord.HookDecision.Applied;
import tantalum.shell.trace.TraceRecord.ModelRequest;
import tantalum.shell.trace.TraceRecord.ModelRequest.Purpose;
import tantalum.shell.trace.TraceRecord.ModelRequest.Status;
import tantalum.shell.trace.TraceRecord.PluginWork;
import tantalum.shell.trace.TraceRecord.RunHead;
import tantalum.shell.trace.TraceRecord.RunHead.Trigger;
import tantalum.shell.trace.TraceRecord.Section;
import tantalum.shell.trace.TraceRecord.Segment;
import tantalum.shell.trace.TraceRecord.Source;
import tantalum.shell.trace.TraceRecord.SystemPrompt;
import tantalum.shell.trace.TraceRecord.UserInput;
import tantalum.value.event.Cancel;
import tantalum.value.event.CompactRequested;
import tantalum.value.event.ModelInterrupted;
import tantalum.value.event.RunInterrupted;
import tantalum.value.hook.Allow;
import tantalum.value.hook.HookContext;
import tantalum.value.hook.HookPoint;
import tantalum.value.hook.HookResult;
import tantalum.value.hook.NeedWork;
import tantalum.value.hook.PromptPatch;
import tantalum.value.hook.Stop;
import tantalum.value.id.RequestId;
import tantalum.value.id.RunId;
import tantalum.value.id.SubmissionId;
import tantalum.value.json.Json;
import tantalum.value.model.FailureReason;
import tantalum.value.model.Message;
import tantalum.value.model.TokenUsage;
import tantalum.value.state.ContextSection;
import tantalum.value.state.EndReason;
import tantalum.value.state.KernelState;

/** 轨迹投影：每种记录、分组、请求编号和重试归并、压缩组、钩子补丁的来源、工作的关联、恢复后接着处理。工具状态在 {@link TraceToolTest}。 */
class TracePassTest {

    /** 模型调用前追加一段 system；会话里有 "halt" 时被 {@link #HALT} 拦下。 */
    private static KernelHook patch(String name, String system, String... tools) {
        return new KernelHook() {
            @Override public String name() { return name; }
            @Override public HookResult beforeModelCall(KernelState state, HookContext context) {
                return new Allow(new PromptPatch(system, List.of(tools).stream().map(TraceFixture::tool).toList()));
            }
            @Override public HookResult beforeToolCall(KernelState state, HookContext context) {
                return new Allow(new PromptPatch(system, List.of()));
            }
        };
    }

    private static boolean said(KernelState state, HookContext context, String text) {
        return state.sessions().get(context.run().session()).messages().stream().anyMatch(message -> message.equals(Message.user(text)));
    }

    private static final KernelHook HALT = new KernelHook() {
        @Override public String name() { return "halt"; }
        @Override public HookResult beforeModelCall(KernelState state, HookContext context) {
            return said(state, context, "halt") ? new Stop("asked to halt") : Allow.PLAIN;
        }
    };

    /** 第一次模型调用前要一轮 {@code lookup} 工作，结果用续写文字 "cited" 交给模型。 */
    private static final KernelHook CITE = new KernelHook() {
        @Override public String name() { return "cite"; }
        @Override public HookResult beforeModelCall(KernelState state, HookContext context) {
            return said(state, context, "cited") ? Allow.PLAIN : new NeedWork("lookup", new Json.Str("q"));
        }
    };

    private static final KernelHook BROKEN = new KernelHook() {
        @Override public String name() { return "broken"; }
        @Override public HookResult beforeModelCall(KernelState state, HookContext context) {
            throw new IllegalStateException("boom");
        }
    };

    @Test
    void firstRunHasHeaderUserInitialSystemAndRequestInStepOne() {
        TraceFixture fx = new TraceFixture();
        RunId run = fx.start("hi");
        fx.feed(run, fx.reply(new TokenUsage(10, 5, 0, 0), text("hello")));

        List<TraceRecord> records = fx.records();
        assertEquals(List.of("run:s_0", "u:0", "s:1", "r:s_1-0"), records.stream().map(TraceRecord::id).toList());
        RunHead head = (RunHead) records.get(0);
        assertEquals(Trigger.PROMPT, head.trigger());
        assertEquals(1, head.head().turn());
        assertNull(head.head().step());
        assertEquals(EndReason.COMPLETED, head.endReason());
        assertEquals(fx.now().minusSeconds(1), head.head().end());
        UserInput user = (UserInput) records.get(1);
        assertEquals(1, user.head().step());
        assertEquals(Source.USER, user.source());
        SystemPrompt system = (SystemPrompt) records.get(2);
        assertTrue(system.initial());
        assertEquals(List.of("read_file"), system.added());
        assertEquals(List.of(new Segment(3, Source.CONTEXT)), system.segments());
        ModelRequest request = (ModelRequest) records.get(3);
        assertEquals(1, request.number());
        assertEquals(1, request.head().step());
        assertEquals(Status.DONE, request.status());
        assertEquals("hello", request.text());
        assertEquals(new TokenUsage(10, 5, 0, 0), request.total());
        assertEquals(1 + RetrySteps.MAX_RETRIES, request.maxAttempts());
        assertEquals(Attempt.Outcome.REPLIED, request.last().outcome());
    }

    @Test
    void requestNumbersAndTotalsContinueAcrossRunsAndRecordsBeforeARequestJoinTheNextStep() {
        TraceFixture fx = new TraceFixture();
        RunId first = fx.start("one");
        fx.feed(first, fx.reply(new TokenUsage(10, 5, 0, 0), call("c1", "read_file")));
        fx.feed(first, fx.steer("also this"));
        fx.feed(first, fx.finished(first, "c1", "body", false));
        fx.feed(first, fx.reply(new TokenUsage(20, 5, 0, 0), text("done")));
        RunId second = fx.start("two");
        fx.feed(second, fx.reply(new TokenUsage(30, 5, 0, 0), text("ok")));

        List<ModelRequest> requests = fx.records(ModelRequest.class);
        assertEquals(List.of(1, 2, 3), requests.stream().map(ModelRequest::number).toList());
        assertEquals(List.of(1, 2, 1), requests.stream().map(request -> request.head().step()).toList());
        assertEquals(List.of(1, 1, 2), requests.stream().map(request -> request.head().turn()).toList());
        assertEquals(new TokenUsage(60, 15, 0, 0), requests.getLast().total());
        UserInput steer = fx.records(UserInput.class).stream().filter(user -> user.mode() == UserInput.Mode.STEER).findFirst().orElseThrow();
        assertEquals(2, steer.head().step(), "a steer waits for the next request");
        assertEquals(2, fx.records(RunHead.class).getLast().head().turn());
        assertEquals(1, fx.records(SystemPrompt.class).size(), "same system and tools are not repeated");
    }

    @Test
    void retryIsANewAttemptOfTheSameRequestWithItsWait() {
        TraceFixture fx = new TraceFixture();
        RunId run = fx.start("hi");
        fx.feed(run, fx.failed(FailureReason.RATE_LIMITED, true, "HTTP 429: slow down"));

        ModelRequest waiting = fx.records(ModelRequest.class).getFirst();
        assertEquals(Status.WAITING, waiting.status());
        assertEquals(Duration.ofSeconds(1), waiting.last().retryAfter());
        assertEquals("RATE_LIMITED", waiting.failure());

        fx.feed(run, fx.timer());
        fx.feed(run, fx.reply(text("ok")));

        List<ModelRequest> requests = fx.records(ModelRequest.class);
        assertEquals(1, requests.size());
        ModelRequest request = requests.getFirst();
        assertEquals(Status.DONE, request.status());
        assertEquals(List.of(Attempt.Outcome.FAILED, Attempt.Outcome.REPLIED), request.attempts().stream().map(Attempt::outcome).toList());
        assertEquals("HTTP 429: slow down", request.attempts().getFirst().detail());
        assertNull(request.failure(), "a request that finally succeeded is not failed");
        assertEquals(1, request.head().step());
    }

    @Test
    void steerDuringARetryWaitMovesToTheCurrentStepWhenTheRetryIsSent() {
        TraceFixture fx = new TraceFixture();
        RunId run = fx.start("hi");
        fx.feed(run, fx.failed(FailureReason.SERVER_ERROR, true, "HTTP 500"));
        fx.feed(run, fx.steer("hurry"));
        long steerSeq = fx.lastSeq();
        assertEquals(2, fx.record("u:" + steerSeq, UserInput.class).head().step(), "first pushed as the next step");

        fx.feed(run, fx.timer());

        assertEquals(1, fx.record("u:" + steerSeq, UserInput.class).head().step());
        assertTrue(fx.pushes.stream().anyMatch(push -> push.seq() == steerSeq + 1 && push.record().id().equals("u:" + steerSeq)),
                "moved record is pushed again");
        assertEquals(1, fx.records(ModelRequest.class).size());
    }

    /** 重试等待中插话让会话超过阈值，定时器到时先发摘要调用：它用途不同，是新请求；被放弃的原请求定为失败。 */
    @Test
    void summaryCallDuringARetryIsANewRequestNotAnAttempt() {
        TraceFixture fx = new TraceFixture();
        RunId first = fx.start(block("q1"), SETTINGS, List.of());
        fx.feed(first, fx.reply(text(block("a1"))));
        RunId earlier = fx.start(block("q2"));
        fx.feed(earlier, fx.reply(new TokenUsage(760, 10, 0, 0), text(block("a2"))));
        RunId second = fx.start("go");
        fx.feed(second, fx.failed(FailureReason.RATE_LIMITED, true, "HTTP 429"));
        fx.feed(second, fx.steer(block("s1")));
        fx.feed(second, fx.timer());

        List<ModelRequest> requests = fx.records(ModelRequest.class);
        assertEquals(4, requests.size(), requests.toString());
        ModelRequest abandoned = requests.get(2);
        assertEquals(Purpose.NORMAL, abandoned.purpose());
        assertEquals(Status.FAILED, abandoned.status());
        assertEquals(1, abandoned.attempts().size());
        ModelRequest summary = requests.get(3);
        assertEquals(Purpose.SUMMARY, summary.purpose());
        assertEquals(4, summary.number());
        assertNull(summary.head().step());
        assertTrue(summary.head().compaction().startsWith("k"));

        fx.feed(second, fx.reply(new TokenUsage(50, 20, 0, 0), text("Goal: q")));
        fx.assembled(second, SETTINGS, List.of());
        fx.feed(second, fx.reply(text("a2")));

        Compacted compacted = fx.records(Compacted.class).getFirst();
        assertTrue(compacted.adopted());
        assertEquals("Goal: q", compacted.summary());
        assertEquals(summary.head().compaction(), compacted.head().compaction());
        ModelRequest after = fx.records(ModelRequest.class).getLast();
        assertEquals(5, after.number());
        assertEquals(2, after.head().step());
        UserInput steer = fx.records(UserInput.class).getLast();
        assertEquals(2, steer.head().step(), "the steer belongs to the request that finally carried it");
    }

    @Test
    void manualCompactionSitsBetweenTurnsAndDoesNotTakeATurnNumber() {
        TraceFixture fx = new TraceFixture();
        RunId first = fx.start(block("q1"), SETTINGS, List.of());
        fx.feed(first, fx.reply(text(block("a1"))));
        RunId second = fx.start(block("q2"));
        fx.feed(second, fx.reply(text(block("a2"))));
        fx.feed(null, new CompactRequested(new SubmissionId("c"), new RequestId("c"), null));
        RunId manual = RunId.of(TraceFixture.SESSION, fx.lastSeq());
        fx.feed(manual, fx.reply(text("Goal: q")));
        RunId third = fx.start("three");
        fx.feed(third, fx.reply(text("a3")));

        RunHead head = fx.record("run:" + manual.value(), RunHead.class);
        assertEquals(Trigger.MANUAL_COMPACTION, head.trigger());
        assertNull(head.head().turn());
        assertEquals("k" + manual.seq(), head.head().compaction());
        ModelRequest summary = fx.record("r:" + fx.steps.get((int) manual.seq()).decision().commands().getFirst().id().value(),
                ModelRequest.class);
        assertEquals(Purpose.SUMMARY, summary.purpose());
        assertEquals(3, summary.number());
        assertEquals(head.head().compaction(), fx.records(Compacted.class).getFirst().head().compaction());
        assertEquals(3, fx.record("run:" + third.value(), RunHead.class).head().turn());
        assertEquals(4, fx.records(ModelRequest.class).getLast().number());
    }

    @Test
    void patchSegmentsAreAttributedToEachHookInOrder() {
        TraceFixture fx = new TraceFixture(true, List.of(patch("alpha", " A-rules", "alpha_tool"), patch("beta", "BB")));
        RunId run = fx.start("hi");

        SystemPrompt system = fx.records(SystemPrompt.class).getFirst();
        assertEquals(List.of(new Segment(3, Source.CONTEXT), new Segment(8, Source.plugin("alpha")), new Segment(2, Source.plugin("beta"))),
                system.segments());
        assertEquals(13, system.systemChars());
        assertEquals(List.of("read_file", "alpha_tool"), system.added());
        Trace.Split split = fx.trace.lastSplit(TraceFixture.SESSION, run);
        assertEquals(List.of(Source.CONTEXT, Source.plugin("alpha")), split.tools());
        List<HookDecision> hooks = fx.records(HookDecision.class);
        assertEquals(List.of(Applied.APPLIED, Applied.APPLIED), hooks.stream().map(HookDecision::applied).toList());
        assertEquals(List.of(8, 2), hooks.stream().map(HookDecision::systemChars).toList());
        assertEquals(1, hooks.getFirst().head().step());
    }

    @Test
    void patchesAreNotAdoptedWhenALaterHookStopsAndIneffectiveBeforeAToolCall() {
        TraceFixture fx = new TraceFixture(true, List.of(patch("alpha", " A"), HALT));
        RunId run = fx.start("hi");
        fx.feed(run, fx.reply(call("c1", "read_file")));
        fx.feed(run, fx.steer("halt"));
        fx.feed(run, fx.finished(run, "c1", "body", false));

        List<HookDecision> hooks = fx.records(HookDecision.class);
        assertEquals(Applied.APPLIED, hooks.get(0).applied());
        HookDecision beforeTool = hooks.get(1);
        assertEquals(HookPoint.BEFORE_TOOL_CALL, beforeTool.point());
        assertEquals(Applied.INEFFECTIVE, beforeTool.applied());
        assertEquals("t:2.0", beforeTool.tool());
        HookDecision notAdopted = hooks.get(2);
        assertEquals(Applied.NOT_ADOPTED, notAdopted.applied());
        HookDecision stop = hooks.get(3);
        assertEquals(HookDecision.Result.STOP, stop.result());
        assertEquals("asked to halt", stop.reason());
        assertEquals(2, stop.head().step(), "stopped before the second request, so it stays in step 2 without a request row");
        assertEquals(EndReason.STOPPED, fx.records(RunHead.class).getFirst().endReason());
    }

    @Test
    void hookAskingForWorkLinksBothWaysAndContinueTextIsAPluginUserRecord() {
        TraceFixture fx = new TraceFixture(true, List.of(CITE));
        RunId run = fx.start("hi");
        PluginWork running = fx.records(PluginWork.class).getFirst();
        assertEquals(PluginWork.Status.RUNNING, running.status());
        assertEquals("cite", running.hook());
        assertEquals(HookPoint.BEFORE_MODEL_CALL, running.point());
        assertEquals(HookPoint.BEFORE_MODEL_CALL, running.resumeAt());
        HookDecision asked = fx.records(HookDecision.class).getFirst();
        assertEquals(HookDecision.Result.NEED_WORK, asked.result());
        assertEquals("lookup", asked.workType());
        assertEquals(running.id(), asked.work());
        assertEquals(asked.id(), running.decision());

        fx.feed(run, fx.workDone(new Json.Str("found"), "cited"));

        PluginWork done = fx.record(running.id(), PluginWork.class);
        assertEquals(PluginWork.Status.DONE, done.status());
        assertEquals(PluginWork.After.CONTINUE, done.after());
        assertEquals(fx.now().minusSeconds(1), done.head().end());
        UserInput cont = fx.records(UserInput.class).getLast();
        assertEquals(UserInput.Mode.CONTINUE, cont.mode());
        assertEquals(Source.plugin("cite"), cont.source());
        assertEquals(1, cont.head().step());
        assertEquals(1, done.head().step());
        assertEquals(1, fx.records(ModelRequest.class).getFirst().head().step());
    }

    @Test
    void hookExceptionIsAnErrorRecordAndAStoppingFailureKnowsItsPoint() {
        TraceFixture fx = new TraceFixture(true, List.of(BROKEN));
        fx.start("hi");

        HookDecision error = fx.records(HookDecision.class).getFirst();
        assertEquals(HookDecision.Result.ERROR, error.result());
        assertTrue(error.reason().contains("boom"), error.reason());
        assertNull(error.point(), "a skipped failure has no point in the fact");
        assertEquals(1, error.head().step());
    }

    @Test
    void contextChangeListsTheChangedSectionsBeforeAndAfter() {
        TraceFixture fx = new TraceFixture();
        RunId first = fx.start("one", null, List.of(new ContextSection("memory", "a", true), new ContextSection("agents", "x", false)));
        fx.feed(first, fx.reply(text("ok")));
        fx.feed(null, fx.prompt("two"));
        RunId second = RunId.of(TraceFixture.SESSION, fx.lastSeq());
        fx.assembled(second, null, List.of(new ContextSection("memory", "a\nb", true)));

        ContextChange change = fx.records(ContextChange.class).getFirst();
        assertEquals(List.of(new Section("memory", "a", "a\nb"), new Section("agents", "x", null)), change.sections());
        assertEquals(2, change.head().turn());
        assertEquals(1, change.head().step());
    }

    @Test
    void queuedFollowUpBecomesTheUserRecordOfTheRunItStarts() {
        TraceFixture fx = new TraceFixture();
        RunId first = fx.start("one");
        fx.feed(first, fx.followUp("later"));
        long queuedSeq = fx.lastSeq();
        assertFalse(fx.records().stream().anyMatch(record -> record.id().equals("u:" + queuedSeq)), "nothing until its run starts");
        fx.feed(first, fx.reply(text("ok")));

        RunHead next = fx.records(RunHead.class).getLast();
        assertEquals(Trigger.FOLLOW_UP, next.trigger());
        UserInput user = fx.record("u:" + queuedSeq, UserInput.class);
        assertEquals(next.head().run(), user.head().run());
        assertEquals(TraceFixture.T0.plusSeconds(queuedSeq), user.head().at());
        assertEquals(UserInput.Mode.FOLLOW_UP, user.mode());
    }

    @Test
    void authFailureGivesOnlyAuthAndNoDetailAnywhere() {
        TraceFixture fx = new TraceFixture();
        RunId run = fx.start("hi");
        fx.feed(run, fx.failed(FailureReason.INVALID_REQUEST, false, "HTTP 401: invalid x-api-key sk-ant-secret"));

        ModelRequest request = fx.records(ModelRequest.class).getFirst();
        assertEquals(Status.FAILED, request.status());
        assertEquals("auth", request.failure());
        assertNull(request.last().detail());
        EventCodec codec = EventCodec.withBuiltins();
        String json = fx.records().stream().map(record -> codec.mapper().writeValueAsString(TraceJson.toMap(codec.mapper(), record)))
                .collect(Collectors.joining("\n"));
        assertFalse(json.contains("sk-ant"), json);
    }

    @Test
    void interruptedRequestKeepsItsPartialTextAlsoWhenTheResultArrivesAfterTheCancel() {
        TraceFixture fx = new TraceFixture();
        RunId run = fx.start("hi");
        fx.feed(run, new ModelInterrupted(fx.callModel(), "half an ans", null, null, null));
        ModelRequest first = fx.records(ModelRequest.class).getFirst();
        assertEquals(Status.INTERRUPTED, first.status());
        assertEquals("half an ans", first.partial());

        RunId second = fx.start("again");
        var call = fx.callModel();
        fx.feed(second, new Cancel(new RequestId("x")));
        assertEquals(Status.INTERRUPTED, fx.records(ModelRequest.class).getLast().status());
        fx.feed(second, new ModelInterrupted(call, "late words", null, null, null));
        assertEquals("late words", fx.records(ModelRequest.class).getLast().partial());
    }

    @Test
    void workInterruptedByARestartEndsWithTheRun() {
        TraceFixture fx = new TraceFixture(true, List.of(CITE));
        RunId run = fx.start("hi");
        fx.feed(run, new RunInterrupted());

        PluginWork work = fx.records(PluginWork.class).getFirst();
        assertEquals(PluginWork.Status.INTERRUPTED, work.status());
        assertEquals(PluginWork.After.END, work.after());
        assertEquals("interrupted by restart", fx.records(RunHead.class).getFirst().endDetail());
    }

    /** 在每个事件之后导出、恢复到新的投影再接着处理，推送和最终状态都和不中断时相同。 */
    @Test
    void restoringSavedStateAndContinuingEqualsNeverStopping() {
        TraceFixture fx = new TraceFixture(true, List.of(patch("alpha", " A", "alpha_tool"), CITE));
        RunId first = fx.start(block("q1"), SETTINGS, List.of(new ContextSection("memory", "a", true)));
        fx.feed(first, fx.workDone(new Json.Str("found"), "cited"));
        fx.feed(first, fx.failed(FailureReason.RATE_LIMITED, true, "HTTP 429"));
        fx.feed(first, fx.steer("more"));
        fx.feed(first, fx.timer());
        fx.feed(first, fx.reply(call("c1", "read_file"), call("c2", "mcp__gh__issue")));
        fx.feed(first, fx.followUp(block("q2")));
        fx.feed(first, fx.finished(first, "c1", "body", false));
        fx.feed(first, fx.finished(first, "c2", " ", true));
        fx.feed(first, fx.reply(new TokenUsage(760, 10, 0, 0), text(block("a1"))));
        RunId second = RunId.of(TraceFixture.SESSION, fx.lastSeq());
        fx.assembled(second, SETTINGS, List.of(new ContextSection("memory", "a\nb", true)));
        fx.feed(second, fx.reply(new TokenUsage(50, 20, 0, 0), text("Goal: q")));
        fx.assembled(second, SETTINGS, List.of());
        fx.feed(second, new RunInterrupted());

        for (int cut = 0; cut <= fx.steps.size(); cut++) {
            Trace early = new Trace();
            for (TraceFixture.Step step : fx.steps.subList(0, cut)) {
                early.apply(step.envelope(), step.before(), step.decision());
            }
            Trace resumed = new Trace();
            resumed.restore(TraceFixture.SESSION, early.export(TraceFixture.SESSION));
            Trace straight = new Trace();
            for (int i = 0; i < fx.steps.size(); i++) {
                TraceFixture.Step step = fx.steps.get(i);
                List<TraceRecord> expected = straight.apply(step.envelope(), step.before(), step.decision());
                if (i >= cut) {
                    assertEquals(expected, resumed.apply(step.envelope(), step.before(), step.decision()), "cut " + cut + ", step " + i);
                }
            }
            assertEquals(straight.export(TraceFixture.SESSION), resumed.export(TraceFixture.SESSION), "cut " + cut);
        }
        Map<Class<?>, Long> kinds = fx.records().stream().collect(Collectors.groupingBy(Object::getClass, Collectors.counting()));
        assertEquals(9, kinds.size(), "every kind appears: " + kinds.keySet());
        EventCodec codec = EventCodec.withBuiltins();
        assertEquals(java.util.Set.of("run", "system", "user", "context", "compacted", "request", "tool", "work", "hook"),
                fx.records().stream().map(record -> TraceJson.toMap(codec.mapper(), record).get("kind")).collect(Collectors.toSet()),
                "no record field may shadow the type name");
        assertTrue(fx.records(TraceRecord.ToolCall.class).stream().anyMatch(tool -> "gh".equals(tool.server())));
    }
}
