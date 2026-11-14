package tantalum.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.plugin.ToolOutput;
import tantalum.shell.plugin.Workspace;
import tantalum.value.id.Principal;
import tantalum.value.id.SessionId;
import tantalum.value.json.Json;

class TruncateOutputHookTest {

    private static final Json ARGS = new Json.Null();
    /** 没有参数的上下文：用构造时的值。 */
    private static final ToolContext CONTEXT = context();

    private static ToolContext context() {
        try {
            return new ToolContext(new Principal("u"), new SessionId("s"), new Workspace(Path.of(".")));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Test
    void shortOutputIsUntouched() {
        ToolOutput output = ToolOutput.ok("short");
        assertSame(output, new TruncateOutputHook(10).afterToolExecution(CONTEXT, "t", ARGS, output));
    }

    /** 留首尾，中间写明省掉多少字符（照 DSH dsh-spill-policy“保留首尾”；tool-scenarios-2 需求 6.4a）。 */
    @Test
    void longOutputKeepsHeadAndTailAndSaysWhatWasLeftOut() {
        ToolOutput cut = new TruncateOutputHook(3).afterToolExecution(CONTEXT, "t", ARGS, ToolOutput.ok("abcdef"));
        assertEquals("a\n[... 3 characters omitted ...]\nef", cut.text());
    }

    /**
     * 工具自己写在结果末尾的话（退出码、完整输出存在哪个文件）不能被这一层切掉（Yi 2026-11-14：“担心工具本身有截断然后再被我们的
     * plugin 再截断 这样信息就会丢失”）。以前只留开头，用户把上限调小时末尾这几句就没了。
     */
    @Test
    void theEndOfAToolResultSurvives() {
        String text = "x".repeat(5000) + "\n\n[Output truncated: ... Full output saved to /tmp/o/command-output-1.log; read parts of it "
                + "with read_file (offset and limit) or search it with grep.]\n\nCommand exited with code 1.";
        ToolOutput cut = new TruncateOutputHook(1000).afterToolExecution(CONTEXT, "bash", ARGS, ToolOutput.error(text));
        assertTrue(cut.text().endsWith("command-output-1.log; read parts of it with read_file (offset and limit) or search it with grep.]"
                + "\n\nCommand exited with code 1."), cut.text());
        assertTrue(cut.text().length() < 1100, String.valueOf(cut.text().length()));
        assertTrue(cut.error());
    }

    /** read_file 的结果不截：模型按行号去读完整输出时不能又被截一次、截了再读（照 DSH dsh-spill-policy 跳过 read）。 */
    @Test
    void readFileResultsAreLeftAlone() {
        ToolOutput output = ToolOutput.ok("y".repeat(5000));
        assertSame(output, new TruncateOutputHook(100).afterToolExecution(CONTEXT, "read_file", ARGS, output));
    }

    /** 截断不能切开代理对，否则两头是坏字符。 */
    @Test
    void surrogatePairsAreNotSplit() {
        ToolOutput cut = new TruncateOutputHook(2).afterToolExecution(CONTEXT, "t", ARGS, ToolOutput.ok("a\uD83D\uDE00b"));
        assertEquals("a\n[... 2 characters omitted ...]\nb", cut.text());
        ToolOutput tail = new TruncateOutputHook(3).afterToolExecution(CONTEXT, "t", ARGS, ToolOutput.ok("ab\uD83D\uDE00"));
        assertEquals("a\n[... 1 characters omitted ...]\n\uD83D\uDE00", tail.text());
    }

    @Test
    void constructorsRejectNonPositiveLimits() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> new TruncateOutputHook(0));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> new DailyQuotaHook(-1));
    }

    @Test
    void errorFlagIsKept() {
        assertEquals(true, new TruncateOutputHook(1).afterToolExecution(CONTEXT, "t", ARGS, ToolOutput.error("abc")).error());
    }
}
