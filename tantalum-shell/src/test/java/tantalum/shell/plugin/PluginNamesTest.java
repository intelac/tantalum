package tantalum.shell.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tantalum.kernel.hook.KernelHook;
import tantalum.value.text.LocalText;

/** 插件页按 {@code plugin()} 分组（core-tools C13）。 */
class PluginNamesTest {

    @Test
    void hooksAndWorkTypesDefaultToTheirOwnName() {
        KernelHook hook = () -> "quota";
        WorkType work = new WorkType() {
            @Override public String name() { return "lookup"; }
            @Override public WorkResult run(ToolContext context, tantalum.value.json.Json input) { return null; }
        };
        assertEquals("quota", hook.plugin());
        // 说明、必须开启、默认开不开在 PluginInfo 上，默认是“无说明、可以关、默认开”（review-fixes 需求 12.6）
        PluginInfo info = new PluginInfo() { };
        assertEquals(LocalText.empty(), info.description());
        assertFalse(info.mandatory());
        assertTrue(info.defaultEnabled());
        assertEquals("lookup", work.plugin());
        assertEquals(LocalText.empty(), work.description());
    }

    static final class Shorten implements AfterToolHook {
        @Override
        public ToolOutput afterToolExecution(ToolContext context, String tool, tantalum.value.json.Json arguments, ToolOutput output) {
            return output;
        }
    }

    /** 工具后处理没有名字，默认取类名；lambda 去掉编译器加的后缀，名字在重启后不变。 */
    @Test
    void afterToolHooksDefaultToTheirClassName() {
        assertEquals("Shorten", new Shorten().plugin());
        AfterToolHook lambda = (context, tool, arguments, output) -> output;
        assertEquals("PluginNamesTest", lambda.plugin());
    }
}
