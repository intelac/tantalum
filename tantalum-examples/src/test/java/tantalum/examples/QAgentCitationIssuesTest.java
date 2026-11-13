package tantalum.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tantalum.value.json.Json;
import tantalum.value.model.ContentBlock;
import tantalum.value.model.Message;
import tantalum.value.model.Role;
import tantalum.value.model.ToolCallBlock;
import tantalum.value.model.ToolResultBlock;

/**
 * QAgent 问题清单第 5、19 条（2026-11-14 Yi 发来）：引用核对的误报。每个测试是一种误报：模型确实看到过、却被当成“引用不存在”。
 */
class QAgentCitationIssuesTest {

    private static List<Message> saw(String tool, Map<String, String> arguments, String output) {
        Map<String, Json> fields = new LinkedHashMap<>();
        arguments.forEach((key, value) -> fields.put(key, new Json.Str(value)));
        ContentBlock call = new ToolCallBlock("c1", tool, "{}", new Json.Obj(fields));
        return List.of(Message.user("question"), new Message(Role.ASSISTANT, List.of(call), false),
                new Message(Role.USER, List.of(new ToolResultBlock("c1", output, false, null)), false));
    }

    private static List<String> wrong(String answer, List<Message> messages) {
        return CitationCheckHook.unsupported(answer, CitationCheckHook.Evidence.of(messages));
    }

    /** 5：调用栈里的 Order.java:42 是日志原文，模型照抄，不是它读过源码的哪一行。 */
    @Test
    void issue5_aStackFrameQuotedFromAToolResultIsNotACitation() {
        List<Message> messages = saw("bash", Map.of("command", "tail app.log"),
                "java.lang.IllegalStateException: total < 0\n\tat com.shop.Order.place(Order.java:42)\n\tat com.shop.Api.post(Api.java:7)");
        assertEquals(List.of(), wrong("The log shows the failure at com.shop.Order.place(Order.java:42).", messages));
    }

    /** 19（一）：别的工具读出的带行号的内容（bash 里的 grep -n、rg -n）也是读过。 */
    @Test
    void issue19_lineNumberedOutputOfOtherToolsCounts() {
        List<Message> messages = saw("bash", Map.of("command", "rg -n total src"), "src/Order.java:12:    int total = a + b;");
        assertEquals(List.of(), wrong("total is computed at src/Order.java:12.", messages));
    }

    /** 19（二）：Windows 路径用反斜杠，读过 C:\shop\src\Order.java，引用 Order.java:2 也算。 */
    @Test
    void issue19_windowsPathsMatch() {
        List<Message> messages = saw("read_file", Map.of("path", "C:\\shop\\src\\Order.java"), "a\nb\nc");
        assertEquals(List.of(), wrong("See Order.java:2 and src\\Order.java:3.", messages));
    }

    /** 19（三）：本机 MCP、git_show 这类工具的结果里原样出现过的 文件:行号，也是模型看到过的。 */
    @Test
    void issue19_aCitationShownVerbatimByAnyToolCounts() {
        List<Message> messages = saw("mcp__jira__get_issue", Map.of("key", "QTR-1"), "Reported: NPE in PaymentService.java:88 when the card is empty");
        assertEquals(List.of(), wrong("The ticket points at PaymentService.java:88.", messages));
    }

    /** 原样出现要整个对上：结果里有 Order.java:42，不能顺带认 Order.java:4，也不能认 MyOrder.java:42。 */
    @Test
    void aVerbatimCitationMustMatchWhole() {
        List<Message> messages = saw("bash", Map.of("command", "tail app.log"), "\tat com.shop.Order.place(Order.java:42)");
        assertEquals(List.of("Order.java:4", "MyOrder.java:42"), wrong("See Order.java:4 and MyOrder.java:42.", messages));
    }
}
