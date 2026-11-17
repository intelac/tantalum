package tantalum.shell.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tantalum.shell.conversation.TranscriptItem.ToolChange;
import tantalum.value.event.ContextAssembled;
import tantalum.value.id.CommandId;
import tantalum.value.id.RunId;
import tantalum.value.json.Json;
import tantalum.value.model.ToolSpec;
import tantalum.value.event.ContextAssembledFixture;
import tantalum.value.event.ToolFinishedFixture;

/** one-line-journal 需求 5：工具变了，对话里在那个位置出一条，写明新增、关掉的和已经没有的。 */
class TranscriptToolChangeTest {

    private static ToolSpec spec(String name) {
        return new ToolSpec(name, name, new Json.Obj(Map.of()));
    }

    /** 提问并组装上下文：{@code offered} 是服务器上这时有的工具，{@code enabled} 是用户开着的。 */
    private static RunId ask(TranscriptFixture fixture, String text, List<String> offered, List<String> enabled) {
        fixture.feed(null, fixture.prompt(text));
        RunId run = RunId.of(TranscriptFixture.SESSION, fixture.lastSeq());
        CommandId assemble = fixture.lastCommands().getFirst().id();
        fixture.feed(run, ContextAssembledFixture.of(assemble, "sys", offered.stream().map(TranscriptToolChangeTest::spec).toList(), null,
                List.of(), enabled, List.of()));
        fixture.feed(run, fixture.reply(TranscriptFixture.text("ok")));
        return run;
    }

    @Test
    void aRemovedToolIsEitherTurnedOffOrGoneAndAnAddedOneIsListed() {
        TranscriptFixture fixture = new TranscriptFixture();
        ask(fixture, "q1", List.of("fetch_url", "grep", "read_file"), List.of("fetch_url", "grep"));
        assertEquals(List.of(), fixture.items(ToolChange.class), "the first assembly is not a change");

        // fetch_url 的插件删了（服务器上没有了），grep 用户关了，read_file 用户打开了
        RunId run = ask(fixture, "q2", List.of("grep", "read_file"), List.of("read_file"));
        ToolChange change = fixture.items(ToolChange.class).getFirst();
        assertEquals(List.of("read_file"), change.added());
        assertEquals(List.of("grep"), change.disabled());
        assertEquals(List.of("fetch_url"), change.gone());
        assertEquals(run, change.run());
    }

    /** local-workspace：条目带上这几个工具的来源，画面据此写原因（本机工作区的写“没有连上或没有允许”）。 */
    @Test
    void theItemCarriesWhereEachToolComesFrom() {
        TranscriptFixture fixture = new TranscriptFixture();
        Map<String, String> sources = Map.of("read_file", "local", "memory_read", "builtin", "lookup", "mcp:demo");
        List<ToolSpec> tools = List.of(spec("lookup"), spec("memory_read"), spec("read_file"));
        fixture.feed(null, fixture.prompt("q1"));
        RunId first = RunId.of(TranscriptFixture.SESSION, fixture.lastSeq());
        fixture.feed(first, ContextAssembledFixture.of(fixture.lastCommands().getFirst().id(), "sys", tools, null, List.of(),
                List.of("lookup", "memory_read", "read_file"), List.of(), sources));
        fixture.feed(first, fixture.reply(TranscriptFixture.text("ok")));
        fixture.feed(null, fixture.prompt("q2"));
        RunId second = RunId.of(TranscriptFixture.SESSION, fixture.lastSeq());
        fixture.feed(second, ContextAssembledFixture.of(fixture.lastCommands().getFirst().id(), "sys", tools, null, List.of(),
                List.of("lookup", "memory_read"), List.of(), sources));

        ToolChange change = fixture.items(ToolChange.class).getFirst();
        assertEquals(List.of("read_file"), change.disabled());
        assertEquals(Map.of("read_file", "local"), change.sources());
    }

    @Test
    void theItemIsTheSameWhenTheConversationIsReplayed() {
        TranscriptFixture fixture = new TranscriptFixture();
        ask(fixture, "q1", List.of("fetch_url", "grep"), List.of("fetch_url", "grep"));
        ask(fixture, "q2", List.of("grep"), List.of("grep"));

        Transcript replayed = new Transcript();
        TranscriptReplay.run(replayed, fixture.kernel(), fixture.envelopes);
        assertEquals(fixture.items(ToolChange.class), replayed.items(TranscriptFixture.SESSION).stream()
                .filter(ToolChange.class::isInstance).toList());
    }

    /** browser-on-demand 需求 2.5：调过入口工具，内核在同一轮里加上其余的，对话里在那个位置出一条“新增”，回放相同。 */
    @Test
    void toolsAddedAfterAnEntryCallAreListedInTheSameRun() {
        TranscriptFixture fixture = new TranscriptFixture();
        fixture.feed(null, fixture.prompt("open the page"));
        RunId run = RunId.of(TranscriptFixture.SESSION, fixture.lastSeq());
        List<ToolSpec> tools = List.of(spec("browser_click"), spec("browser_navigate"), spec("browser_type"));
        fixture.feed(run, new ContextAssembled(fixture.lastCommands().getFirst().id(), "sys", tools, null, List.of(),
                List.of("browser_navigate"), List.of(), Map.of(), null, null,
                List.of(new tantalum.value.model.ToolUnlock(List.of("browser_navigate"), List.of("browser_click", "browser_type")))));
        assertEquals(List.of(), fixture.items(ToolChange.class));
        fixture.feed(run, fixture.reply(TranscriptFixture.call("a", "browser_navigate")));
        tantalum.value.command.RunTool tool = fixture.runTools().getFirst();
        fixture.feed(run, ToolFinishedFixture.of(tool.id(), "a", "page", false, null));

        ToolChange change = fixture.items(ToolChange.class).getFirst();
        assertEquals(List.of("browser_click", "browser_type"), change.added());
        assertEquals(List.of(), change.disabled());
        assertEquals(run, change.run());

        Transcript replayed = new Transcript();
        TranscriptReplay.run(replayed, fixture.kernel(), fixture.envelopes);
        assertEquals(fixture.items(ToolChange.class), replayed.items(TranscriptFixture.SESSION).stream()
                .filter(ToolChange.class::isInstance).toList());
    }
}
