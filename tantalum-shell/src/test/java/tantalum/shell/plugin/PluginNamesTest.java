package tantalum.shell.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import tantalum.kernel.hook.KernelHook;

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
        assertEquals("", hook.description());
        assertEquals("lookup", work.plugin());
        assertEquals("", work.description());
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
