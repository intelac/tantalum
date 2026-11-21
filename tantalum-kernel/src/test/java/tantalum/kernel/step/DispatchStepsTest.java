package tantalum.kernel.step;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tantalum.kernel.LoopFixture.text;

import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.kernel.LoopFixture;
import tantalum.value.assignment.AssignmentOutcome;
import tantalum.value.command.Acknowledge;
import tantalum.value.command.AssembleContext;
import tantalum.value.command.CallModel;
import tantalum.value.command.Command;
import tantalum.value.command.Deliver;
import tantalum.value.decision.Decision;
import tantalum.value.delivery.AssignmentParcel;
import tantalum.value.delivery.NoticeParcel;
import tantalum.value.delivery.ReportParcel;
import tantalum.value.event.Assignment;
import tantalum.value.event.ContextAssembledFixture;
import tantalum.value.event.Delivered;
import tantalum.value.event.ModelReplied;
import tantalum.value.event.Notice;
import tantalum.value.event.Report;
import tantalum.value.event.SessionCreated;
import tantalum.value.event.UserPromptFixture;
import tantalum.value.id.RequestId;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;
import tantalum.value.id.SessionIds;
import tantalum.value.id.SubmissionId;
import tantalum.value.json.Json;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.NoticeBlock;
import tantalum.value.model.ReportBlock;
import tantalum.value.model.StopReason;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.state.RunCause;
import tantalum.value.state.SessionKind;

/**
 * dispatch 需求 2、3：主会话的 task 开一个派活的普通会话（或交给点名的那个）；那边为它跑一轮，结果交回主会话；
 * 同一个会话先后收几次交代、重复送达只确认；你直接在那边说话的那一轮结束时，主会话记一笔，不为它调模型。
 */
class DispatchStepsTest {

    private static final SessionId MAIN = SessionIds.main(new tantalum.value.id.Principal("u"));

    private static <T> T only(Decision decision, Class<T> type) {
        List<T> found = decision.commands().stream().filter(type::isInstance).map(type::cast).toList();
        assertEquals(1, found.size(), decision.commands().toString());
        return found.getFirst();
    }

    private static ToolCallBlock task(String id, String prompt, String session) {
        java.util.Map<String, Json> fields = new java.util.LinkedHashMap<>();
        fields.put("prompt", new Json.Str(prompt));
        if (session != null) {
            fields.put("session", new Json.Str(session));
        }
        return new ToolCallBlock(id, "task", "{}", new Json.Obj(fields));
    }

    /** 主会话开一轮，模型调 task，再说完。返回放出的那条送达。 */
    private static Deliver dispatch(LoopFixture loop, String text, String session) {
        Decision asked = loop.feed(MAIN, null, UserPromptFixture.of(new SubmissionId("s" + text), new RequestId("r" + text), text));
        RunId run = loop.state.sessions().get(MAIN).activeRun();
        Decision ready = loop.feed(MAIN, run, ContextAssembledFixture.of(only(asked, AssembleContext.class).id(), "s", List.of(), null, List.of()));
        Decision called = loop.feed(MAIN, run, new ModelReplied(only(ready, CallModel.class).id(), List.of(task("c-" + text, text, session)),
                StopReason.TOOL_USE, "m", null, null, null, null));
        Deliver deliver = only(called, Deliver.class);
        loop.feed(MAIN, run, new ModelReplied(only(called, CallModel.class).id(), text("handed out"), StopReason.END_TURN, "m", null, null, null, null));
        return deliver;
    }

    /** 像处理器那样送到：派活会话还没有就按交代建，然后把回执送回。返回放进交代的那一步。 */
    private static Decision arrive(LoopFixture loop, Deliver deliver) {
        AssignmentParcel given = (AssignmentParcel) deliver.what();
        if (loop.state.sessions().get(deliver.to()) == null) {
            loop.create(deliver.to(), new SessionCreated(SessionKind.REGULAR, null, false, null, MAIN));
        }
        Decision arrived = loop.feed(deliver.to(), null, given.input());
        AssignmentFixture.acknowledge(loop, arrived);
        return arrived;
    }

    /** 派活会话里正在跑的那一轮：组装上下文、模型说一句就结束。返回结束那一步。 */
    private static Decision answer(LoopFixture loop, SessionId session, Decision started, String said) {
        RunId run = loop.state.sessions().get(session).activeRun();
        Decision ready = loop.feed(session, run, ContextAssembledFixture.of(only(started, AssembleContext.class).id(), "s", List.of(), null, List.of()));
        return loop.feed(session, run, new ModelReplied(only(ready, CallModel.class).id(), text(said), StopReason.END_TURN, "m", null, null, null, null));
    }

    @Test
    void theMainSessionHandsAJobToARegularSessionAndItsResultComesBack() {
        LoopFixture loop = new LoopFixture();
        loop.create(MAIN, new SessionCreated(SessionKind.MAIN, null, false, null, null));
        Deliver deliver = dispatch(loop, "check module A", null);
        String id = ((AssignmentParcel) deliver.what()).assignment();
        assertEquals(SessionIds.work(MAIN, id), deliver.to(), "a new work session, not a helper");
        assertTrue(!SessionIds.helperShaped(deliver.to()) && !SessionIds.mainShaped(deliver.to()) && SessionIds.workShaped(deliver.to()));

        Decision arrived = arrive(loop, deliver);
        SessionId work = deliver.to();
        RunId run = loop.state.sessions().get(work).activeRun();
        assertEquals(new RunCause(RunCause.StartedBy.ASSIGNMENT, tantalum.value.event.Root.USER, id, MAIN, null),
                LoopFixture.plain(loop.state.runs().get(run).cause()));
        Decision ended = answer(loop, work, arrived, "Module A is fine.");

        Deliver back = only(ended, Deliver.class);
        assertEquals(MAIN, back.to(), "the result goes to whoever gave the task");
        Report report = ((ReportParcel) back.what()).input();
        assertEquals(AssignmentOutcome.COMPLETED, report.outcome());
        assertEquals("Module A is fine.", report.text());
        assertTrue(ended.commands().stream().noneMatch(command -> command instanceof Deliver d && d.what() instanceof NoticeParcel),
                "a run for a task sends its result, not a note");
    }

    @Test
    void theSameSessionTakesASecondTaskAndARepeatIsOnlyConfirmed() {
        LoopFixture loop = new LoopFixture();
        loop.create(MAIN, new SessionCreated(SessionKind.MAIN, null, false, null, null));
        Deliver first = dispatch(loop, "first", null);
        Decision arrived = arrive(loop, first);
        SessionId work = first.to();
        answer(loop, work, arrived, "first done");

        Deliver second = dispatch(loop, "second", work.value());
        assertEquals(work, second.to(), "handed to the named conversation");
        Decision again = arrive(loop, second);
        Decision ended = answer(loop, work, again, "second done");
        // 给主会话的第一个结果还没确认：第二个在信箱里排着（同一个对方前一条确认了才发下一条）
        assertEquals("second done", ((ReportParcel) last(loop, work)).input().text(), "only this run's answer, not the first one's");

        Decision repeat = loop.feed(work, null, ((AssignmentParcel) second.what()).input());
        List<Command> commands = repeat.commands();
        assertEquals(1, commands.size(), commands.toString());
        assertTrue(commands.getFirst() instanceof Acknowledge ack && ack.refused() == null);
    }

    @Test
    void aRegularSessionTakesTasksOnlyFromItsOwnersMainSession() {
        LoopFixture loop = new LoopFixture();
        SessionId work = new SessionId("work-u-9-0");
        loop.create(work, new SessionCreated(SessionKind.REGULAR, null, false, null, MAIN));
        Decision other = loop.feed(work, null, new Assignment("t9-0", new SessionId("s"), tantalum.value.event.Root.USER, "do it", null));
        assertTrue(other.commands().getFirst() instanceof Acknowledge ack && ack.refused() != null, other.commands().toString());
        assertNull(loop.state.sessions().get(work).activeRun());
    }

    /** 你直接在派活会话里说：那边照常跑；结束时主会话记一笔，只进历史，主会话不开轮。 */
    @Test
    void whatYouSayInTheWorkSessionIsNotedInTheMainSessionWithoutAModelCall() {
        LoopFixture loop = new LoopFixture();
        loop.create(MAIN, new SessionCreated(SessionKind.MAIN, null, false, null, null));
        Deliver first = dispatch(loop, "first", null);
        Decision arrived = arrive(loop, first);
        SessionId work = first.to();
        answer(loop, work, arrived, "first done");
        loop.feed(MAIN, null, ((ReportParcel) only(loop.last, Deliver.class).what()).input());
        RunId mainRun = loop.state.sessions().get(MAIN).activeRun();
        if (mainRun != null) {
            // 结果回来开的那一轮说完
            Decision ready = loop.feed(MAIN, mainRun, ContextAssembledFixture.of(((AssembleContext) loop.last.commands().stream()
                    .filter(AssembleContext.class::isInstance).findFirst().orElseThrow()).id(), "s", List.of(), null, List.of()));
            loop.feed(MAIN, mainRun, new ModelReplied(only(ready, CallModel.class).id(), text("noted"), StopReason.END_TURN, "m", null, null, null, null));
        }

        Decision said = loop.feed(work, null, UserPromptFixture.of(new SubmissionId("u1"), new RequestId("u1"), "also check B"));
        Decision ended = answer(loop, work, said, "B is fine too.");
        assertEquals(MAIN, loop.state.sessions().get(work).outbox().getLast().to());
        Notice notice = ((NoticeParcel) last(loop, work)).input();
        assertTrue(notice.text().contains("also check B") && notice.text().contains("B is fine too."), notice.text());

        int before = loop.state.sessions().get(MAIN).historySize();
        Decision noted = loop.feed(MAIN, null, notice);
        assertNull(loop.state.sessions().get(MAIN).activeRun(), "no run for a note");
        assertTrue(noted.commands().stream().noneMatch(AssembleContext.class::isInstance));
        assertEquals(before + 1, loop.state.sessions().get(MAIN).historySize());
        ContentBlock block = loop.state.sessions().get(MAIN).messages().getLast().content().getFirst();
        assertTrue(block instanceof NoticeBlock, block.toString());
        int after = loop.state.sessions().get(MAIN).historySize();
        loop.feed(MAIN, null, notice);
        assertEquals(after, loop.state.sessions().get(MAIN).historySize(), "a repeated note is recorded once");
    }

    /** 这个会话信箱里最后记下的一条。 */
    private static tantalum.value.delivery.Parcel last(LoopFixture loop, SessionId session) {
        return loop.state.sessions().get(session).outbox().getLast().what();
    }

    /** 为交代跑的那一轮里不能再放帮手：结果一交回就没人要它们的结果了。 */
    @Test
    void aRunDoingATaskCannotStartHelpers() {
        LoopFixture loop = new LoopFixture();
        loop.create(MAIN, new SessionCreated(SessionKind.MAIN, null, false, null, null));
        Deliver first = dispatch(loop, "first", null);
        Decision arrived = arrive(loop, first);
        SessionId work = first.to();
        RunId run = loop.state.sessions().get(work).activeRun();
        Decision ready = loop.feed(work, run, ContextAssembledFixture.of(only(arrived, AssembleContext.class).id(), "s", List.of(), null, List.of()));
        Decision called = loop.feed(work, run, new ModelReplied(only(ready, CallModel.class).id(), List.of(task("c9", "sub job", null)),
                StopReason.TOOL_USE, "m", null, null, null, null));
        assertTrue(called.commands().stream().noneMatch(Deliver.class::isInstance), "no helper is started");
        assertTrue(loop.state.sessions().get(work).assignments().open().isEmpty());
    }

    @Test
    void theMainSessionsResultSettlesInItsListAsBefore() {
        LoopFixture loop = new LoopFixture();
        loop.create(MAIN, new SessionCreated(SessionKind.MAIN, null, false, null, null));
        Deliver deliver = dispatch(loop, "check", null);
        String id = ((AssignmentParcel) deliver.what()).assignment();
        loop.feed(MAIN, null, new Delivered(deliver.what().id()));
        loop.feed(MAIN, null, new Report(id, AssignmentOutcome.COMPLETED, "done", tantalum.value.event.Root.USER, deliver.to()));
        assertTrue(loop.state.sessions().get(MAIN).assignments().open().isEmpty());
        boolean shown = loop.state.sessions().get(MAIN).view().stream().flatMap(message -> message.content().stream())
                .anyMatch(block -> block instanceof ReportBlock report && report.assignment().equals(id));
        boolean queued = loop.state.sessions().get(MAIN).inbox().stream().flatMap(item -> item.message().content().stream())
                .anyMatch(block -> block instanceof ReportBlock report && report.assignment().equals(id));
        assertTrue(shown || queued);
    }

    /** 主会话正在跑时来了一笔：排着，下次调模型前带上，不为它多调一次（审查第 1 条）。 */
    @Test
    void aNoteThatArrivesWhileTheMainSessionRunsIsCarriedWithoutAnExtraCall() {
        LoopFixture loop = new LoopFixture();
        loop.create(MAIN, new SessionCreated(SessionKind.MAIN, null, false, null, null));
        Decision asked = loop.feed(MAIN, null, UserPromptFixture.of(new SubmissionId("s1"), new RequestId("r1"), "how are things"));
        RunId run = loop.state.sessions().get(MAIN).activeRun();
        loop.feed(MAIN, null, new Notice("work-u-1-0_5", new SessionId("work-u-1-0"), "The user said: hi\nIt answered: hello"));
        assertEquals(1, loop.state.sessions().get(MAIN).inbox().size(), "waits for the next model call");

        Decision ready = loop.feed(MAIN, run, ContextAssembledFixture.of(only(asked, AssembleContext.class).id(), "s", List.of(), null, List.of()));
        CallModel call = only(ready, CallModel.class);
        assertTrue(call.prompt().messages().stream().flatMap(message -> message.content().stream()).anyMatch(NoticeBlock.class::isInstance),
                "the note is in the call");
        assertTrue(loop.state.sessions().get(MAIN).inbox().isEmpty());
        Decision done = loop.feed(MAIN, run, new ModelReplied(call.id(), text("all fine"), StopReason.END_TURN, "m", null, null, null, null));
        assertTrue(done.commands().stream().noneMatch(CallModel.class::isInstance), "no extra call for the note");
        assertNull(loop.state.sessions().get(MAIN).activeRun());
    }
}
