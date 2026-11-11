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

    @Test
    void longOutputIsCutAndSaysSo() {
        ToolOutput cut = new TruncateOutputHook(3).afterToolExecution(CONTEXT, "t", ARGS, ToolOutput.ok("abcdef"));
        assertTrue(cut.text().startsWith("abc"));
        assertTrue(cut.text().contains("3 more characters"));
    }

    /** 截断不能切开代理对，否则末尾是一个坏字符。 */
    @Test
    void surrogatePairsAreNotSplit() {
        ToolOutput cut = new TruncateOutputHook(2).afterToolExecution(CONTEXT, "t", ARGS, ToolOutput.ok("a😀b"));
        assertEquals("a", cut.text().split("\n")[0]);
        assertTrue(cut.text().contains("3 more characters"));
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
