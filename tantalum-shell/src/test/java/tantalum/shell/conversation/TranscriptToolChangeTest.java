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
        fixture.feed(run, new ContextAssembled(assemble, "sys", offered.stream().map(TranscriptToolChangeTest::spec).toList(), null,
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
}
