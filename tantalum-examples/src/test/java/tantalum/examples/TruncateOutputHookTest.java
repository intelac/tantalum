package tantalum.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tantalum.shell.plugin.ToolOutput;
import tantalum.value.json.Json;

class TruncateOutputHookTest {

    private static final Json ARGS = new Json.Null();

    @Test
    void shortOutputIsUntouched() {
        ToolOutput output = ToolOutput.ok("short");
        assertSame(output, new TruncateOutputHook(10).afterToolExecution(null, "t", ARGS, output));
    }

    @Test
    void longOutputIsCutAndSaysSo() {
        ToolOutput cut = new TruncateOutputHook(3).afterToolExecution(null, "t", ARGS, ToolOutput.ok("abcdef"));
        assertTrue(cut.text().startsWith("abc"));
        assertTrue(cut.text().contains("3 more characters"));
    }

    @Test
    void errorFlagIsKept() {
        assertEquals(true, new TruncateOutputHook(1).afterToolExecution(null, "t", ARGS, ToolOutput.error("abc")).error());
    }
}
