package tantalum.shell.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.kernel.hook.KernelHook;
import tantalum.shell.journal.EventCodec;
import tantalum.shell.journal.Line;
import tantalum.shell.journal.SessionFileReader;
import tantalum.shell.journal.SessionFiles;
import tantalum.shell.loop.SessionSlice;
import tantalum.value.command.AssembleContext;
import tantalum.value.command.CallModel;
import tantalum.value.command.Command;
import tantalum.value.decision.Decision;
import tantalum.value.decision.StepRecord;
import tantalum.value.event.ContextAssembled;
import tantalum.value.event.Event;
import tantalum.value.event.EventEnvelope;
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
import tantalum.value.json.Json;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tantalum.value.state.KernelState;
import tantalum.value.state.OwnerRef;
import tantalum.value.state.SessionState;
import tantalum.value.event.UserPromptFixture;

/**
 * plugin-settings 任务 20（P9，需求 5.4、5.5）：带参数的会话，读结果行恢复出来的和重放内核得到的、和当时内存里的都一样；
 * 中途改参数也一样；没记过参数的旧会话接着用，旧的行一个字节不动，新的行带上参数。
 */
class PluginParamsReplayTest {

    private static final Principal ALICE = ReplayScenario.ALICE;
    private static final SessionId SESSION = new SessionId("params");

    /** 额度看参数：tokens 小于 10 就停。没有参数时放行。 */
    private static final KernelHook QUOTA = new KernelHook() {
        @Override public String name() { return "quotaHook"; }
        @Override public String plugin() { return "quota"; }
        @Override public HookResult beforeModelCall(KernelState state, HookContext context) {
            return context.params().number("tokens", 1000) < 10 ? new Stop("quota used up") : Allow.PLAIN;
        }
    };

    @TempDir
    Path dir;

    private ReplayScenario scenario;
    private int requests;

    @BeforeEach
    void start() throws Exception {
        scenario = new ReplayScenario(dir, List.of(QUOTA));
    }

    @AfterEach
    void stop() throws Exception {
        scenario.close();
    }

    /** 键故意不按字母序：声明的顺序要原样留着。 */
    private static Json.Obj params(long tokens) {
        Map<String, Json> guard = new LinkedHashMap<>();
        guard.put("tools", new Json.Arr(List.of(new Json.Str("write_file"))));
        guard.put("maxChars", new Json.Num(BigDecimal.valueOf(500)));
        Map<String, Json> all = new LinkedHashMap<>();
        all.put("guard", new Json.Obj(guard));
        all.put("quota", new Json.Obj(Map.of("tokens", new Json.Num(BigDecimal.valueOf(tokens)))));
        return new Json.Obj(all);
    }

    private interface Sender {
        Decision send(RunId run, Event event) throws Exception;
    }

    /** 问一次：提交、组装（带这次的参数）、模型放行了就回答。返回组装那一步的决定。 */
    private Decision ask(Sender sender, Json.Obj params) throws Exception {
        String request = "req-" + requests++;
        Decision submitted = sender.send(null, UserPromptFixture.of(new SubmissionId("sub-" + request), new RequestId(request),
                new OwnerRef.OfSession(SESSION), "question " + request));
        AssembleContext assemble = (AssembleContext) submitted.commands().getFirst();
        RunId run = assemble.run().run();
        Decision assembled = sender.send(run, new ContextAssembled(assemble.id(), "system", List.of(), null, List.of(), null,
                List.of("guard", "quota"), Map.of(), null, params));
        for (Command command : assembled.commands()) {
            if (command instanceof CallModel call) {
                sender.send(run, new ModelReplied(call.id(), List.of(new TextBlock("ok")), StopReason.END_TURN, "m", null, null, null, null));
            }
        }
        return assembled;
    }

    private Decision ask(Json.Obj params) throws Exception {
        return ask((run, event) -> scenario.fixture.send(ALICE, SESSION, run, ReplayScenario.AT, event), params);
    }

    private static boolean called(Decision decision) {
        return decision.commands().stream().anyMatch(CallModel.class::isInstance);
    }

    private RecordReplay.Loaded restored() throws Exception {
        return new RecordReplay(scenario.paths, new SessionFileReader(EventCodec.withBuiltins()))
                .load(ALICE, SESSION, Long.MAX_VALUE).orElseThrow();
    }

    private SessionSlice replayed() throws Exception {
        scenario.writer.flush();
        return SessionReplayFixture.of(scenario.paths, new SessionFileReader(EventCodec.withBuiltins()),
                new UsageLedger(scenario.index, scenario.writer), scenario.kernel).load(ALICE, SESSION, Long.MAX_VALUE).orElseThrow();
    }

    private SessionState live() {
        return scenario.fixture.state().sessions().get(SESSION);
    }

    @Test
    void aSessionWithParamsRestoresAndReplaysTheSame() throws Exception {
        assertTrue(called(ask(params(500))));
        assertTrue(called(ask(params(500))));

        SessionSlice restored = restored().slice();
        assertEquals(live(), restored.session());
        assertEquals(replayed().session(), restored.session());
        assertEquals(params(500), restored.session().context().pluginParams());
        // 读回来的键顺序和写的时候一样
        assertEquals(List.of("guard", "quota"), List.copyOf(restored.session().context().pluginParams().fields().keySet()));
        assertEquals(List.of("tools", "maxChars"),
                List.copyOf(((Json.Obj) restored.session().context().pluginParams().fields().get("guard")).fields().keySet()));

        // 参数没变：文件里只出现一次（第一次组装的事件和它冻结的上下文各一处）
        String text = Files.readString(scenario.paths.sessionFile(ALICE, SESSION));
        assertEquals(2, text.split("\"pluginParams\"", -1).length - 1, text);
    }

    /** 中途改了参数、后来又没了：每一步都按当时记下的那份走，恢复和重放都一样。 */
    @Test
    void changingParamsMidwayRestoresAndReplaysTheSame() throws Exception {
        assertTrue(called(ask(params(500))));
        assertFalse(called(ask(params(5))), "the smaller quota stops the model call");
        assertEquals(params(5), live().context().pluginParams());
        assertTrue(called(ask(null)), "without params the hook falls back to its own default");
        assertNull(live().context().pluginParams());
        assertTrue(called(ask(params(700))));

        SessionSlice restored = restored().slice();
        assertEquals(live(), restored.session());
        SessionSlice replayed = replayed();
        assertEquals(replayed.session(), restored.session());
        assertEquals(replayed.transcript(), restored.transcript());
        assertEquals(params(700), restored.session().context().pluginParams());
    }

    /**
     * 没记过参数的旧会话，重启后接着用：旧的行不动；新的组装带上参数；读回来的状态和内存里的一样。
     * 再问一次参数没变：从文件读回来的那份和新算出来的要相等，上下文不换（数字两边的写法不同就会在这里露出来）。
     */
    @Test
    void anOldSessionCarriesOnWithParams() throws Exception {
        assertTrue(called(ask(null)));
        assertTrue(called(ask(null)));
        Path file = scenario.paths.sessionFile(ALICE, SESSION);
        scenario.fixture.files.close();
        String old = Files.readString(file);
        assertFalse(old.contains("pluginParams"));

        // 重启：从结果行恢复，接着写
        RecordReplay.Loaded loaded = restored();
        List<KernelState> states = new ArrayList<>(List.of(KernelState.empty().withResident(loaded.slice().session(), loaded.slice().runs())));
        long[] seq = {loaded.slice().seq()};
        try (SessionFiles files = new SessionFiles(scenario.paths, EventCodec.withBuiltins(), 4, (p, s, n, offset) -> { })) {
            files.adopt(ALICE, SESSION, loaded.memo());
            Sender sender = (run, event) -> {
                EventEnvelope envelope = new EventEnvelope(++seq[0], ReplayScenario.AT, ALICE, SESSION, run, event);
                KernelState before = states.getLast();
                Decision decision = scenario.kernel.step(before, envelope);
                files.append(ALICE, SESSION, List.of(new Line(envelope, StepRecord.of(envelope, before, decision))));
                states.add(decision.newState());
                return decision;
            };
            assertFalse(called(ask(sender, params(5))), "the new params reach the hook in the old session");
            assertTrue(called(ask(sender, params(500))));
        }
        String now = Files.readString(file);
        assertTrue(now.startsWith(old), "the old lines are untouched");
        assertTrue(now.substring(old.length()).contains("\"pluginParams\""));

        RecordReplay.Loaded again = restored();
        SessionState inMemory = states.getLast().sessions().get(SESSION);
        assertEquals(inMemory, again.slice().session());
        assertEquals(params(500), again.slice().session().context().pluginParams());

        // 第二次重启后参数没变：上下文原样留着
        KernelState cold = KernelState.empty().withResident(again.slice().session(), again.slice().runs());
        long[] next = {again.slice().seq()};
        List<KernelState> after = new ArrayList<>(List.of(cold));
        Sender dry = (run, event) -> {
            Decision decision = scenario.kernel.step(after.getLast(), new EventEnvelope(++next[0], ReplayScenario.AT, ALICE, SESSION, run, event));
            after.add(decision.newState());
            return decision;
        };
        assertTrue(called(ask(dry, params(500))));
        assertSame(again.slice().session().context(), after.getLast().sessions().get(SESSION).context(),
                "params read back from the file equal the ones computed live");
    }
}
