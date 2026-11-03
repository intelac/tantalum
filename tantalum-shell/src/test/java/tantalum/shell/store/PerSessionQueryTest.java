package tantalum.shell.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.kernel.Kernel;
import tantalum.kernel.Limits;
import tantalum.kernel.hook.HookChain;
import tantalum.kernel.hook.KernelHook;
import tantalum.kernel.step.BuiltinSteps;
import tantalum.kernel.table.TransitionTable;
import tantalum.shell.conversation.Transcript;
import tantalum.shell.conversation.TranscriptItem;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.journal.SessionFileReader;
import tantalum.shell.journal.SessionPaths;
import tantalum.shell.sse.SseHub;
import tantalum.shell.sse.SseMessage;
import tantalum.value.command.AssembleContext;
import tantalum.value.command.CallModel;
import tantalum.value.decision.Decision;
import tantalum.value.decision.Fact;
import tantalum.value.event.ContextAssembled;
import tantalum.value.event.Event;
import tantalum.value.event.ModelReplied;
import tantalum.value.event.UserPrompt;
import tantalum.value.hook.Allow;
import tantalum.value.hook.HookContext;
import tantalum.value.hook.HookResult;
import tantalum.value.hook.Stop;
import tantalum.value.id.Principal;
import tantalum.value.id.RequestId;
import tantalum.value.id.RunId;
import tantalum.value.id.SessionId;
import tantalum.value.id.SubmissionId;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tantalum.value.model.TokenUsage;
import tantalum.value.state.KernelState;
import tantalum.value.state.OwnerRef;

/**
 * 任务 33、34：{@code /turns}、{@code /trace} 只读运行所在的会话文件，SSE 补读只读该用户的文件，
 * 结果和实时相同，包括依赖其他会话用量的钩子结果。
 */
class PerSessionQueryTest {

    private static final Principal ALICE = new Principal("alice");
    private static final Principal BOB = new Principal("bob");
    private static final SessionId FIRST = new SessionId("first");
    private static final SessionId SECOND = new SessionId("second");
    private static final SessionId BOBS = new SessionId("bobs");
    private static final Instant AT = Instant.parse("2026-11-02T01:00:00Z");
    private static final long QUOTA = 1000;

    private static final KernelHook QUOTA_HOOK = new KernelHook() {
        @Override public String name() { return "quota"; }
        @Override public HookResult beforeModelCall(KernelState state, HookContext context) {
            var principal = state.principals().get(context.run().principal());
            if (principal == null) {
                return Allow.PLAIN;
            }
            TokenUsage today = principal.usageOn(LocalDate.ofInstant(context.at(), ZoneOffset.UTC));
            return today.promptTotal() + today.output() >= QUOTA ? new Stop("quota") : Allow.PLAIN;
        }
    };

    @TempDir
    Path dir;

    private SessionIndex index;
    private IndexWriter writer;
    private IndexFixture fixture;
    private SessionPaths paths;
    private TurnQuery query;
    /** 实时推给 alice 的 SSE 消息，用来和补读比较。 */
    private final List<SseMessage> live = new ArrayList<>();
    private final Transcript transcript = new Transcript();

    @BeforeEach
    void start() throws Exception {
        TransitionTable table = new TransitionTable();
        BuiltinSteps.register(table);
        Kernel kernel = new Kernel(table, new HookChain(List.of(QUOTA_HOOK)), (tool, args) -> false, Limits.DEFAULT);
        index = SessionIndex.open(dir);
        writer = new IndexWriter(index, 100, Duration.ofHours(1));
        paths = new SessionPaths(dir);
        fixture = new IndexFixture(paths, writer::onDurable, writer::onStep, kernel);
        SessionReplay replay = new SessionReplay(paths, new SessionFileReader(EventCodec.withBuiltins()), new UsageLedger(index, writer), kernel);
        query = new TurnQuery(paths, replay, EventCodec.withBuiltins().mapper());
    }

    @AfterEach
    void stop() throws Exception {
        fixture.files.close();
        writer.close();
        index.close();
    }

    private Decision send(Principal principal, SessionId session, RunId run, Event event) throws Exception {
        Decision decision = fixture.send(principal, session, run, AT, event);
        if (principal.equals(ALICE)) {
            long seq = fixture.events.getLast().seq();
            int at = 0;
            for (Fact fact : decision.facts()) {
                live.add(SseHub.factMessage(EventCodec.withBuiltins().mapper(), seq, at++, fact));
            }
            for (TranscriptItem item : transcript.apply(fixture.events.getLast(), decision)) {
                live.add(SseHub.itemMessage(EventCodec.withBuiltins().mapper(), seq, at++, item));
            }
        }
        return decision;
    }

    /** 提交并组装上下文，返回运行；钩子放行时还有一条模型调用。 */
    private record Started(RunId run, CallModel call) {}

    private Started prompt(Principal principal, SessionId session, String text) throws Exception {
        Decision submitted = send(principal, session, null,
                new UserPrompt(new SubmissionId("sub-" + text), new RequestId(text), new OwnerRef.OfSession(session), text));
        AssembleContext assemble = (AssembleContext) submitted.commands().getFirst();
        Decision assembled = send(principal, session, assemble.run().run(), new ContextAssembled(assemble.id(), "s", List.of(), null, List.of()));
        CallModel call = assembled.commands().isEmpty() ? null : (CallModel) assembled.commands().getFirst();
        return new Started(assemble.run().run(), call);
    }

    private void reply(Principal principal, SessionId session, Started started) throws Exception {
        send(principal, session, started.run(), new ModelReplied(started.call().id(), List.of(new TextBlock("ok")), StopReason.END_TURN, "m",
                new TokenUsage(990, 10, 0, 0)));
    }

    /** alice 在第一个会话用掉 1000，第二个会话被额度钩子停下；bob 不受影响。两个用户的事件交错。 */
    private RunId scenario() throws Exception {
        Started first = prompt(ALICE, FIRST, "first");
        Started bobs = prompt(BOB, BOBS, "bob");
        reply(ALICE, FIRST, first);
        reply(BOB, BOBS, bobs);
        Started second = prompt(ALICE, SECOND, "second");
        assertEquals(null, second.call(), "the live kernel stopped it");
        writer.flush();
        return second.run();
    }

    private void unreadable(Principal principal, SessionId session) throws Exception {
        Files.setPosixFilePermissions(paths.sessionFile(principal, session), PosixFilePermissions.fromString("---------"));
    }

    @Test
    void theTraceOfAStoppedRunReadsOnlyItsSessionAndStillSeesTheOtherSessionsUsage() throws Exception {
        RunId stopped = scenario();
        unreadable(ALICE, FIRST);
        unreadable(BOB, BOBS);
        TurnQuery.Trace trace = query.trace(ALICE, stopped);
        assertEquals(List.of("AwaitingContext", "Ended"), trace.events().stream().map(TurnQuery.Trace.Entry::phase).toList());
        assertTrue(query.turns(ALICE, stopped).isEmpty(), "no model call was made");
        assertTrue(query.trace(BOB, stopped).events().isEmpty(), "someone else's run is like a missing one");
    }

    @Test
    void turnsOfARunAreTheLivePrompts() throws Exception {
        Started first = prompt(ALICE, FIRST, "first");
        reply(ALICE, FIRST, first);
        writer.flush();
        prompt(ALICE, SECOND, "other session");
        unreadable(ALICE, SECOND);
        List<TurnQuery.Turn> turns = query.turns(ALICE, first.run());
        assertEquals(1, turns.size());
        assertEquals(first.call().prompt(), turns.getFirst().prompt());
    }

    @Test
    void catchUpEqualsWhatWasPushedLiveAndIgnoresOtherUsers() throws Exception {
        scenario();
        unreadable(BOB, BOBS);
        assertEquals(live, query.messagesAfter(ALICE, "-1.0"));
        String middle = live.get(live.size() / 2).id();
        List<SseMessage> after = live.subList(live.size() / 2 + 1, live.size());
        assertEquals(after, query.messagesAfter(ALICE, middle));
    }

    /** 补读不读索引的会话表：索引里一行都没有时也不缺事件。 */
    @Test
    void catchUpDoesNotDependOnTheIndex() throws Exception {
        prompt(ALICE, FIRST, "first");
        prompt(ALICE, SECOND, "second");
        assertTrue(index.sessions(ALICE).isEmpty(), "no run has ended, so nothing was flushed");
        assertEquals(live, query.messagesAfter(ALICE, "-1.0"));
    }
}
