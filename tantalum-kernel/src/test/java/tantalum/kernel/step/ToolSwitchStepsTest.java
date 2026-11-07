package tantalum.kernel.step;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static tantalum.kernel.LoopFixture.text;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tantalum.kernel.LoopFixture;
import tantalum.value.event.ContextAssembled;
import tantalum.value.event.ModelReplied;
import tantalum.value.json.Json;
import tantalum.value.model.Message;
import tantalum.value.model.Role;
import tantalum.value.model.StopReason;
import tantalum.value.model.ToolChangeBlock;
import tantalum.value.model.ToolSpec;
import tantalum.value.state.SessionContext;

/** core-tools C14：每次提问开始时按用户现在的开关换名单，变了就追加一条系统消息。 */
class ToolSwitchStepsTest {

    private static final List<ToolSpec> ALL = List.of(spec("grep"), spec("read_file"), spec("write_file"));

    private static ToolSpec spec(String name) {
        return new ToolSpec(name, name, new Json.Obj(Map.of()));
    }

    private static void ask(LoopFixture loop, String text, List<String> tools, List<String> plugins) {
        loop.prompt(text);
        loop.feed(loop.runId(), new ContextAssembled(loop.onlyCommandId(), "s", ALL, null, List.of(), tools, plugins));
        loop.feed(loop.runId(), new ModelReplied(loop.onlyCommandId(), text("ok"), StopReason.END_TURN, "m", null, null, null, null));
    }

    private static Message change(List<String> added, List<String> removed) {
        return new Message(Role.SYSTEM, List.of(new ToolChangeBlock(added, removed)), false);
    }

    /** 历史里的消息，去掉模型的回复，只看用户消息和系统消息。 */
    private static List<Message> notReplies(LoopFixture loop) {
        return loop.session().messages().stream().filter(message -> message.role() != Role.ASSISTANT).toList();
    }

    @Test
    void theFirstAssemblyRecordsTheWholeSetAndWhatWasOn() {
        LoopFixture loop = new LoopFixture();
        ask(loop, "q1", List.of("grep", "read_file"), List.of("quota"));
        SessionContext context = loop.session().context();
        assertEquals(ALL, context.tools(), "the tool list is the whole set, it never changes");
        assertEquals(List.of("grep", "read_file"), context.initialTools());
        assertEquals(List.of("grep", "read_file"), context.activeTools());
        assertEquals(List.of("quota"), context.activePlugins());
        assertEquals(List.of(Message.user("q1")), notReplies(loop));
    }

    @Test
    void turningAToolOnAddsAChangeAfterTheNextQuestion() {
        LoopFixture loop = new LoopFixture();
        ask(loop, "q1", List.of("grep", "read_file"), List.of());
        ask(loop, "q2", List.of("grep", "read_file", "write_file"), List.of());
        assertEquals(List.of(Message.user("q1"), Message.user("q2"), change(List.of("write_file"), List.of())), notReplies(loop));
        assertEquals(List.of("grep", "read_file", "write_file"), loop.session().context().activeTools());
        assertEquals(List.of("grep", "read_file"), loop.session().context().initialTools(), "the start does not move");
        // 系统消息紧跟在提问后面、模型回复前面
        List<Message> all = loop.session().messages();
        assertEquals(Role.SYSTEM, all.get(all.size() - 2).role());
        assertEquals(Role.ASSISTANT, all.getLast().role());
    }

    @Test
    void turningToolsOffListsThemInTheOrderOfTheWholeSet() {
        LoopFixture loop = new LoopFixture();
        ask(loop, "q1", List.of("write_file", "grep", "read_file"), List.of());
        ask(loop, "q2", List.of("read_file"), List.of());
        assertEquals(change(List.of(), List.of("grep", "write_file")), notReplies(loop).getLast());
    }

    @Test
    void addingAndRemovingAtOnceIsOneMessage() {
        LoopFixture loop = new LoopFixture();
        ask(loop, "q1", List.of("read_file"), List.of());
        ask(loop, "q2", List.of("write_file"), List.of());
        assertEquals(change(List.of("write_file"), List.of("read_file")), notReplies(loop).getLast());
    }

    @Test
    void nothingChangedAddsNothing() {
        LoopFixture loop = new LoopFixture();
        ask(loop, "q1", List.of("read_file"), List.of("quota"));
        ask(loop, "q2", List.of("read_file"), List.of("quota"));
        assertEquals(List.of(Message.user("q1"), Message.user("q2")), notReplies(loop));
    }

    /** 钩子插件模型看不见：名单换了只记下，不发消息。 */
    @Test
    void pluginChangesAreRecordedButNotTold() {
        LoopFixture loop = new LoopFixture();
        ask(loop, "q1", List.of("read_file"), List.of("quota", "notes"));
        ask(loop, "q2", List.of("read_file"), List.of("quota"));
        assertEquals(List.of(Message.user("q1"), Message.user("q2")), notReplies(loop));
        assertEquals(List.of("quota"), loop.session().context().activePlugins());
    }

    /** 会话开始之后才出现的工具（新连上的 MCP）不在全集里，开着也不加进来。 */
    @Test
    void toolsOutsideTheWholeSetAreIgnored() {
        LoopFixture loop = new LoopFixture();
        ask(loop, "q1", List.of("read_file"), List.of());
        ask(loop, "q2", List.of("read_file", "mcp__new__tool"), List.of());
        assertEquals(List.of(Message.user("q1"), Message.user("q2")), notReplies(loop));
        assertEquals(List.of("read_file"), loop.session().context().activeTools());
    }

    /** 加名单之前的会话：事件里没有名单，读成全部开着，什么都不追加（需求 8 第 6 条）。 */
    @Test
    void oldSessionsWithoutListsStayAllOn() {
        LoopFixture loop = new LoopFixture();
        ask(loop, "q1", null, null);
        ask(loop, "q2", null, null);
        SessionContext context = loop.session().context();
        assertNull(context.initialTools());
        assertNull(context.activeTools());
        assertNull(context.activePlugins());
        assertEquals(List.of(Message.user("q1"), Message.user("q2")), notReplies(loop));
    }

    /** 旧会话升级后第一次带名单组装：全部开着的状态和用户现在的开关比，关掉的写成移除。 */
    @Test
    void anOldSessionMeetsTheSwitches() {
        LoopFixture loop = new LoopFixture();
        ask(loop, "q1", null, null);
        ask(loop, "q2", List.of("grep", "read_file"), List.of());
        assertEquals(change(List.of(), List.of("write_file")), notReplies(loop).getLast());
        assertEquals(List.of("grep", "read_file"), loop.session().context().activeTools());
    }

    /** review-fixes 需求 5.6：第一次冻结时开着的名单只认全集里有的名字。 */
    @Test
    void theFirstAssemblyKeepsOnlyToolsOfTheWholeSet() {
        LoopFixture loop = new LoopFixture();
        ask(loop, "q1", List.of("grep", "ghost"), List.of());
        SessionContext context = loop.session().context();
        assertEquals(List.of("grep"), context.initialTools());
        assertEquals(List.of("grep"), context.activeTools());
    }
}
