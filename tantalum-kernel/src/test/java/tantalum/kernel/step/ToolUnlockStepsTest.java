package tantalum.kernel.step;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tantalum.kernel.LoopFixture.call;
import static tantalum.kernel.LoopFixture.text;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tantalum.kernel.LoopFixture;
import tantalum.value.command.CallModel;
import tantalum.value.command.Command;
import tantalum.value.command.RunTool;
import tantalum.value.event.ContextAssembled;
import tantalum.value.event.ModelReplied;
import tantalum.value.event.ToolFinished;
import tantalum.value.json.Json;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.Message;
import tantalum.value.model.Role;
import tantalum.value.model.StopReason;
import tantalum.value.model.ToolChangeBlock;
import tantalum.value.model.ToolSpec;
import tantalum.value.model.ToolUnlock;
import tantalum.value.event.ToolFinishedFixture;

/**
 * browser-on-demand 需求 2：调过入口工具以后，同一轮的下一次调用模型就有其余的工具；只加一次；下一次提问、压缩后都留着；
 * 用户关掉的去掉；旧会话不变。
 */
class ToolUnlockStepsTest {

    private static final List<String> ENTRY = List.of("browser_navigate", "browser_tabs");
    private static final List<String> REST = List.of("browser_click", "browser_type");
    private static final List<ToolSpec> ALL = List.of(spec("browser_click"), spec("browser_navigate"), spec("browser_tabs"),
            spec("browser_type"), spec("grep"));
    private static final List<ToolUnlock> RULES = List.of(new ToolUnlock(ENTRY, REST));

    private static ToolSpec spec(String name) {
        return new ToolSpec(name, name, new Json.Obj(Map.of()));
    }

    /** 提问、组装（开着入口和 grep，规则把其余两个按需加）。 */
    private static void ask(LoopFixture loop, String text, List<ToolUnlock> rules) {
        loop.prompt(text);
        loop.feed(loop.runId(), new ContextAssembled(loop.onlyCommandId(), "s", ALL, null, List.of(),
                List.of("browser_navigate", "browser_tabs", "grep"), List.of(), Map.of(), null, null, rules));
    }

    private static void reply(LoopFixture loop, ContentBlock... calls) {
        loop.feed(loop.runId(), new ModelReplied(loop.onlyCommandId(), List.of(calls), StopReason.TOOL_USE, "m", null, null, null, null));
    }

    private static void finishAll(LoopFixture loop, boolean error) {
        for (Command command : loop.last.commands()) {
            RunTool run = (RunTool) command;
            loop.feed(loop.runId(), ToolFinishedFixture.of(run.id(), run.call().id(), "done", error, null));
        }
    }

    private static void answer(LoopFixture loop) {
        loop.feed(loop.runId(), new ModelReplied(loop.onlyCommandId(), text("ok"), StopReason.END_TURN, "m", null, null, null, null));
    }

    /** 这次调用模型发出的、没标 deferred 的工具。 */
    private static List<String> offered(LoopFixture loop) {
        CallModel model = (CallModel) loop.onlyCommand();
        return model.prompt().tools().stream().filter(tool -> !tool.deferred()).map(ToolSpec::name).toList();
    }

    private static long changes(LoopFixture loop) {
        return loop.session().messages().stream().filter(message -> message.role() == Role.SYSTEM)
                .flatMap(message -> message.content().stream()).filter(ToolChangeBlock.class::isInstance).count();
    }

    @Test
    void atTheStartOnlyTheEntryToolsAreOffered() {
        LoopFixture loop = new LoopFixture();
        ask(loop, "q1", RULES);
        assertEquals(List.of("browser_navigate", "browser_tabs", "grep"), offered(loop));
        assertEquals(List.of("browser_navigate", "browser_tabs", "grep"), loop.session().context().activeTools());
    }

    @Test
    void callingAnEntryToolAddsTheRestBeforeTheNextModelCallInTheSameRun() {
        LoopFixture loop = new LoopFixture();
        ask(loop, "q1", RULES);
        reply(loop, call("a", "browser_navigate"));
        finishAll(loop, false);
        assertEquals(List.of("browser_click", "browser_navigate", "browser_tabs", "browser_type", "grep"),
                loop.session().context().activeTools());
        List<Message> messages = loop.session().messages();
        assertEquals(new Message(Role.SYSTEM, List.of(new ToolChangeBlock(REST, List.of())), false), messages.getLast(),
                "the change comes right after the tool results");
        assertEquals(Role.USER, messages.get(messages.size() - 2).role());
        assertTrue(loop.onlyCommand() instanceof CallModel);
        assertTrue(loop.session().toolsSeen().containsAll(REST));
    }

    @Test
    void aFailedEntryCallCountsButOtherToolsDoNot() {
        LoopFixture other = new LoopFixture();
        ask(other, "q1", RULES);
        reply(other, call("a", "grep"));
        finishAll(other, false);
        assertEquals(0, changes(other));

        LoopFixture failed = new LoopFixture();
        ask(failed, "q1", RULES);
        reply(failed, call("a", "browser_tabs"));
        finishAll(failed, true);
        assertEquals(1, changes(failed));
    }

    @Test
    void theRestIsAddedOnlyOnceAndStaysForTheNextQuestion() {
        LoopFixture loop = new LoopFixture();
        ask(loop, "q1", RULES);
        reply(loop, call("a", "browser_navigate"));
        finishAll(loop, false);
        reply(loop, call("b", "browser_navigate"));
        finishAll(loop, false);
        answer(loop);
        assertEquals(1, changes(loop));

        ask(loop, "q2", RULES);
        assertEquals(1, changes(loop), "the next question keeps them without another change");
        // 后加的工具在请求里仍标 deferred，靠历史里的工具变化开启（同插件页中途打开工具）；这里看会话里开着的
        assertEquals(List.of("browser_click", "browser_navigate", "browser_tabs", "browser_type", "grep"),
                loop.session().context().activeTools());
    }

    /** 用户在插件页关掉了 browser_type：服务器算规则时已经去掉它，下一次提问照现有规则移除。 */
    @Test
    void aToolTheUserTurnsOffLaterIsRemoved() {
        LoopFixture loop = new LoopFixture();
        ask(loop, "q1", RULES);
        reply(loop, call("a", "browser_navigate"));
        finishAll(loop, false);
        answer(loop);
        ask(loop, "q2", List.of(new ToolUnlock(ENTRY, List.of("browser_click"))));
        assertEquals(new Message(Role.SYSTEM, List.of(new ToolChangeBlock(List.of(), List.of("browser_type"))), false),
                loop.session().messages().getLast());
        assertEquals(List.of("browser_click", "browser_navigate", "browser_tabs", "grep"), loop.session().context().activeTools());
    }

    /** 没有规则（模型不支持工具变化、旧事件）：照旧，调了入口也不加。 */
    @Test
    void withoutRulesNothingIsAdded() {
        LoopFixture loop = new LoopFixture();
        ask(loop, "q1", null);
        reply(loop, call("a", "browser_navigate"));
        finishAll(loop, false);
        assertEquals(0, changes(loop));
    }
}
