package tantalum.shell.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tantalum.value.model.ToolCallBlock;

/** 2026-11-16 LongMemEval：qwen 把工具调用按 Qwen3 的写法写进了正文。整段都是调用块、名字是给了的工具时才认。 */
class TextToolCallsTest {

    private static final Set<String> OFFERED = Set.of("web_search", "memory_search");

    @Test
    void aReplyThatIsOnlyToolCallsBecomesToolCalls() {
        String text = """
                <tool_call>
                <function=web_search>
                <parameter=queries>
                ["how many weeks from March 27 to April 19 2023", "weeks between March 27 and April 19"]
                </parameter>
                <parameter=depth>
                3
                </parameter>
                </function>
                </tool_call>
                <tool_call>
                <function=memory_search>
                <parameter=query>
                exchange program orientation
                </parameter>
                </function>
                </tool_call>""";
        List<ToolCallBlock> calls = TextToolCalls.parse(text, OFFERED, "x").orElseThrow();
        assertEquals(List.of("web_search", "memory_search"), calls.stream().map(ToolCallBlock::name).toList());
        assertEquals("{\"queries\":[\"how many weeks from March 27 to April 19 2023\",\"weeks between March 27 and April 19\"],\"depth\":3}",
                calls.getFirst().rawArguments());
        assertEquals("{\"query\":\"exchange program orientation\"}", calls.get(1).rawArguments());
        assertEquals("text-call-1-x", calls.getFirst().id());
    }

    @Test
    void anythingElseStaysText() {
        String call = "<tool_call><function=web_search><parameter=query>x</parameter></function></tool_call>";
        assertTrue(TextToolCalls.parse("Here is how I would search:\n" + call, OFFERED, "x").isEmpty(), "words around the call");
        assertTrue(TextToolCalls.parse(call + "\nDone.", OFFERED, "x").isEmpty());
        assertTrue(TextToolCalls.parse(call.replace("web_search", "bash"), OFFERED, "x").isEmpty(), "a tool that was not offered");
        assertTrue(TextToolCalls.parse("<tool_call><function=web_search>stray<parameter=query>x</parameter></function></tool_call>",
                OFFERED, "x").isEmpty());
        assertTrue(TextToolCalls.parse("<tool_call><function=web_search><parameter=query>x</function></tool_call>", OFFERED, "x").isEmpty(),
                "an unfinished parameter");
        assertTrue(TextToolCalls.parse("The answer is 17 skeins.", OFFERED, "x").isEmpty());
    }
}
