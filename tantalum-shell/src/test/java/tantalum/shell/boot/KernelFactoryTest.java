package tantalum.shell.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import tantalum.kernel.tool.ToolSafety;
import tantalum.shell.fetch.WebFetchTool;
import tantalum.shell.local.LocalTool;
import tantalum.shell.memory.MemoryTools;
import tantalum.shell.plugin.ToolDispatch;
import tantalum.shell.tool.BuiltinTools;
import tantalum.value.json.Json;

/** review-fixes 需求 12.3：检查工具的调度规则从工具的描述来，不造带 null 依赖的工具，答案和真目录相同。 */
class KernelFactoryTest {

    @TempDir
    Path temp;

    private TantalumProperties properties(String... overrides) throws Exception {
        Properties values = new Properties();
        try (InputStream defaults = TantalumProperties.class.getResourceAsStream("/application.properties")) {
            values.load(defaults);
        }
        values.setProperty("tantalum.data-dir", temp.resolve("data").toString());
        values.setProperty("tantalum.workspace", temp.resolve("work").toString());
        for (int i = 0; i < overrides.length; i += 2) {
            values.setProperty(overrides[i], overrides[i + 1]);
        }
        return new Binder(new MapConfigurationPropertySource(values)).bind("tantalum", TantalumProperties.class).get();
    }

    private static Json args(String... pairs) {
        Map<String, Json> fields = new java.util.LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            fields.put(pairs[i], new Json.Str(pairs[i + 1]));
        }
        return new Json.Obj(fields);
    }

    @Test
    void theSafetyAnswersFromTheDescriptionsWithoutOpeningAnything() throws Exception {
        ToolSafety safety = KernelFactory.safety(properties("tantalum.web.fetch.enabled", "true"), List.of());
        assertTrue(safety.isConcurrencySafe("read_attachment", args("id", "a")));
        assertFalse(safety.isConcurrencySafe("ask_user", args()));
        assertTrue(safety.isConcurrencySafe("read_file", args("path", "a.txt")));
        assertFalse(safety.isConcurrencySafe("bash", args("command", "ls")));
        assertTrue(safety.isConcurrencySafe("web_fetch", args("url", "https://example.com")));
        assertTrue(safety.isConcurrencySafe("memory_read", args("name", "x")));
        assertFalse(safety.isConcurrencySafe("browser_navigate", args("url", "https://example.com")), "unregistered: exclusive");
        assertFalse(safety.isConcurrencySafe("mcp__s__search", args()), "MCP tools are always exclusive");
        assertFalse(Files.exists(temp.resolve("data")), "nothing was opened");

        ToolSafety noFetch = KernelFactory.safety(properties("tantalum.web.fetch.enabled", "false"), List.of());
        assertFalse(noFetch.isConcurrencySafe("web_fetch", args("url", "https://example.com")));
    }

    /** 静态的描述和工具自己的方法说的一样。 */
    @Test
    void theStaticDescriptionsMatchTheTools() {
        assertEquals(List.of("read_attachment", "ask_user"), BuiltinTools.dispatch().stream().map(ToolDispatch::name).toList());
        assertEquals(LocalTool.all(null, null, java.time.Duration.ofSeconds(1)).stream().map(tool -> tool.dispatch().name()).toList(),
                LocalTool.dispatches().stream().map(ToolDispatch::name).toList());
        assertEquals(List.of("memory_write", "memory_read", "memory_delete"), MemoryTools.dispatch().stream().map(ToolDispatch::name).toList());
        assertEquals(new WebFetchTool(null).server(), WebFetchTool.DISPATCH.server());
        assertEquals(16, WebFetchTool.DISPATCH.serverConcurrency());
    }

    /** review-fixes 需求 12.4：内核只认钩子自己的 failClosed；必须开启的插件（自己声明或管理员配置）在这里包成 fail-closed。 */
    @Test
    void requiredAndMandatoryPluginsAreWrappedFailClosed() {
        tantalum.kernel.hook.KernelHook plain = hook("metrics", false);
        tantalum.kernel.hook.KernelHook declared = new Mandatory();
        tantalum.kernel.hook.KernelHook already = hook("approval", true);
        List<tantalum.kernel.hook.KernelHook> wrapped = KernelFactory.failClosed(List.of(plain, declared, already, hook("other", false)),
                java.util.Set.of("metrics"));
        assertTrue(wrapped.get(0).failClosed(), "required by the administrator");
        assertEquals("metrics", wrapped.get(0).name());
        assertTrue(wrapped.get(1).failClosed(), "declared mandatory");
        assertEquals(already, wrapped.get(2), "already fail-closed: untouched");
        assertEquals(wrapped.get(3).failClosed(), false);
        tantalum.value.hook.HookResult result = wrapped.get(0).beforeModelCall(tantalum.value.state.KernelState.empty(), null);
        assertEquals(new tantalum.value.hook.Stop("metrics ran"), result, "the callbacks still reach the original hook");
    }

    private static tantalum.kernel.hook.KernelHook hook(String name, boolean failClosed) {
        return new tantalum.kernel.hook.KernelHook() {
            @Override public String name() { return name; }
            @Override public boolean failClosed() { return failClosed; }
            @Override public tantalum.value.hook.HookResult beforeModelCall(tantalum.value.state.KernelState state, tantalum.value.hook.HookContext context) {
                return new tantalum.value.hook.Stop(name + " ran");
            }
        };
    }

    static final class Mandatory implements tantalum.kernel.hook.KernelHook, tantalum.shell.plugin.PluginInfo {
        @Override public String name() { return "quota"; }
        @Override public boolean mandatory() { return true; }
    }
}
