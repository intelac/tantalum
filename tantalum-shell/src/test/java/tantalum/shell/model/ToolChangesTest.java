package tantalum.shell.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import tantalum.value.json.Json;
import tantalum.value.model.Message;
import tantalum.value.model.Prompt;
import tantalum.value.model.Role;
import tantalum.value.model.ToolChangeBlock;
import tantalum.value.model.ToolSpec;

/** one-line-journal 需求 4.3：不支持追加式工具变化的模型，每次变化都在文字里重申仍不能用的工具。 */
class ToolChangesTest {

    @Test
    void theTextRepeatsToolsThatAreStillUnavailable() {
        assertEquals(tantalum.value.model.SystemUpdates.HEADER + "Your tools changed. No longer available: read_file. Still unavailable: write_file.",
                ToolChanges.describe(new ToolChangeBlock(List.of(), List.of("read_file"), List.of("write_file"))));
        assertEquals(tantalum.value.model.SystemUpdates.HEADER + "Your tools changed. Now available: grep.",
                ToolChanges.describe(new ToolChangeBlock(List.of("grep"), List.of())));
    }

    /**
     * browser-on-demand：OpenAI 兼容的路线第一次只发入口工具；同一轮里工具结果后面跟着一条工具变化，下一次请求就带上其余的。
     */
    @Test
    void toolsAddedAfterAToolResultAreSentInTheNextRequest() {
        ToolSpec navigate = new ToolSpec("browser_navigate", "d", new Json.Obj(java.util.Map.of()));
        ToolSpec click = new ToolSpec("browser_click", "d", new Json.Obj(java.util.Map.of())).deferred(true);
        List<ToolSpec> tools = List.of(click, navigate);
        assertEquals(List.of("browser_navigate"), ToolChanges.active(new Prompt("s", tools, List.of(Message.user("open it"))))
                .stream().map(ToolSpec::name).toList());
        List<Message> later = List.of(Message.user("open it"), Message.user("[result]"),
                new Message(Role.SYSTEM, List.of(new ToolChangeBlock(List.of("browser_click"), List.of())), false));
        assertEquals(List.of("browser_click", "browser_navigate"), ToolChanges.active(new Prompt("s", tools, later))
                .stream().map(ToolSpec::name).toList());
    }
}
