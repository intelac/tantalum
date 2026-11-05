package tantalum.kernel.step;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tantalum.kernel.LoopFixture.text;
import static tantalum.kernel.step.CompactStepsTest.SETTINGS;
import static tantalum.kernel.step.CompactStepsTest.block;
import static tantalum.kernel.step.CompactStepsTest.endTurn;
import static tantalum.kernel.step.CompactStepsTest.reply;
import static tantalum.kernel.step.CompactStepsTest.thinkingAndCall;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tantalum.kernel.LoopFixture;
import tantalum.kernel.projection.ContextUpdate;
import tantalum.kernel.projection.ToolsGone;
import tantalum.value.command.CallModel;
import tantalum.value.decision.ContextCompacted;
import tantalum.value.event.ContextAssembled;
import tantalum.value.event.ModelReplied;
import tantalum.value.event.ToolFinished;
import tantalum.value.json.Json;
import tantalum.value.model.Message;
import tantalum.value.model.Role;
import tantalum.value.model.StopReason;
import tantalum.value.model.TextBlock;
import tantalum.value.model.TokenUsage;
import tantalum.value.model.ToolChangeBlock;
import tantalum.value.model.ToolSpec;

/** one-line-journal 需求 4：工具没了要告诉模型，压缩之后也一样；之后每次变化都重申仍不能用的工具。 */
class ToolsGoneStepsTest {

    private static final List<ToolSpec> ALL = List.of(spec("fetch_url"), spec("grep"), spec("read_file"), spec("write_file"));

    private static ToolSpec spec(String name) {
        return new ToolSpec(name, name, new Json.Obj(Map.of()));
    }

    private static ContextAssembled assembled(LoopFixture loop, List<String> enabled) {
        return new ContextAssembled(loop.onlyCommandId(), "system", ALL, SETTINGS, List.of(), enabled, List.of());
    }

    private static void ask(LoopFixture loop, String text, List<String> enabled) {
        loop.prompt(text);
        loop.feed(loop.runId(), assembled(loop, enabled));
        loop.feed(loop.runId(), new ModelReplied(loop.onlyCommandId(), text("ok"), StopReason.END_TURN, "m", null, null, null, null));
    }

    private static ToolChangeBlock lastChange(LoopFixture loop) {
        Message last = loop.session().messages().stream().filter(message -> message.role() == Role.SYSTEM).reduce((a, b) -> b).orElseThrow();
        return assertInstanceOf(ToolChangeBlock.class, last.content().getFirst());
    }

    @Test
    void eachLaterChangeRepeatsTheToolsThatAreStillUnavailable() {
        LoopFixture loop = new LoopFixture();
        ask(loop, "q1", List.of("grep", "read_file", "write_file"));
        ask(loop, "q2", List.of("grep", "read_file"));
        assertEquals(new ToolChangeBlock(List.of(), List.of("write_file"), List.of()), lastChange(loop));

        ask(loop, "q3", List.of("grep"));
        assertEquals(new ToolChangeBlock(List.of(), List.of("read_file"), List.of("write_file")), lastChange(loop));
        assertEquals(List.of("grep", "read_file", "write_file"), loop.session().toolsSeen());
    }

    @Test
    void toolsThatWereNeverOnAreNotReportedAsGone() {
        LoopFixture loop = new LoopFixture();
        ask(loop, "q1", List.of("grep"));
        ask(loop, "q2", List.of("grep"));
        assertEquals(List.of("grep"), loop.session().toolsSeen());
        assertTrue(loop.session().messages().stream().noneMatch(message -> message.role() == Role.SYSTEM));
    }

    /**
     * 用过 fetch_url，插件删了，接着问：先照旧追加工具变化消息；这一问超过阈值要压缩，压缩把那条消息压进摘要，
     * 重新组装后内核再用一条上下文更新告诉模型 fetch_url 不能用了，这条更新就在发给模型的请求里。
     */
    @Test
    void afterACompactionTheModelIsToldAgainWhichToolsAreGone() {
        LoopFixture loop = new LoopFixture();
        loop.prompt(block("q1"));
        loop.feed(loop.runId(), assembled(loop, List.of("fetch_url", "grep")));
        loop.feed(loop.runId(), reply(loop, thinkingAndCall("t1"), StopReason.TOOL_USE, new TokenUsage(100, 10, 0, 0)));
        loop.feed(loop.runId(), new ToolFinished(loop.onlyCommandId(), "t1", block("r1"), false, null));
        loop.feed(loop.runId(), endTurn(loop, block("a1"), 790));
        loop.prompt(block("q2"));
        loop.feed(loop.runId(), assembled(loop, List.of("grep")));
        assertEquals(new ToolChangeBlock(List.of(), List.of("fetch_url"), List.of()), lastChange(loop));
        assertEquals("AwaitingSummary", loop.phase());

        loop.feed(loop.runId(), reply(loop, text("Goal: q1, used fetch_url"), StopReason.END_TURN, new TokenUsage(50, 20, 0, 0)));
        assertTrue(loop.last.facts().stream().anyMatch(ContextCompacted.class::isInstance));
        loop.feed(loop.runId(), assembled(loop, List.of("grep")));

        CallModel call = assertInstanceOf(CallModel.class, loop.onlyCommand());
        Message told = call.prompt().messages().getLast();
        assertEquals(Role.USER, told.role());
        String text = ((TextBlock) told.content().getFirst()).text();
        assertTrue(ContextUpdate.isUpdate(text), text);
        assertTrue(text.endsWith(ToolsGone.text(List.of("fetch_url"))), text);
        assertEquals(List.of("fetch_url", "grep"), loop.session().toolsSeen(), "compaction does not forget which tools were on");
    }

    @Test
    void afterACompactionNothingIsSaidWhenNoToolWentAway() {
        LoopFixture loop = new LoopFixture();
        loop.prompt(block("q1"));
        loop.feed(loop.runId(), assembled(loop, List.of("grep")));
        loop.feed(loop.runId(), reply(loop, thinkingAndCall("t1"), StopReason.TOOL_USE, new TokenUsage(100, 10, 0, 0)));
        loop.feed(loop.runId(), new ToolFinished(loop.onlyCommandId(), "t1", block("r1"), false, null));
        loop.feed(loop.runId(), endTurn(loop, block("a1"), 790));
        loop.prompt(block("q2"));
        loop.feed(loop.runId(), assembled(loop, List.of("grep")));
        loop.feed(loop.runId(), reply(loop, text("Goal: q1"), StopReason.END_TURN, new TokenUsage(50, 20, 0, 0)));
        int before = loop.session().messages().size();
        loop.feed(loop.runId(), assembled(loop, List.of("grep")));

        assertEquals(before, loop.session().messages().size());
    }
}
