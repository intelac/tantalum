package tantalum.shell.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tantalum.shell.mcp.TestMcpServer.Definition;
import tantalum.shell.plugin.Tool;
import tantalum.shell.plugin.ToolContext;
import tantalum.shell.tool.ToolCatalog;
import tantalum.value.id.Principal;
import tantalum.value.json.Json;

@Timeout(60)
class McpRegistryTest {

    private static final McpConnection.Policy FAST = new McpConnection.Policy(Duration.ofMillis(20), Duration.ofMillis(100), 5,
            Duration.ofSeconds(60), Duration.ofMillis(100), Duration.ofMillis(100));

    private TestMcpServer server;
    private McpRegistry registry;

    @AfterEach
    void stop() throws Exception {
        if (registry != null) {
            registry.close();
        }
        if (server != null) {
            server.close();
        }
    }

    private static McpSettings.Server settings(String url, Set<String> allow, Set<String> deny, int maxTools) {
        return new McpSettings.Server("s", URI.create(url), McpSettings.Auth.NONE, null, allow, deny, 2, Duration.ofSeconds(5), 1024, maxTools);
    }

    private void open(Set<String> allow, Set<String> deny, int maxTools) throws Exception {
        server = new TestMcpServer(
                new Definition("search", true, args -> TestMcpServer.text("found")),
                new Definition("create", false, args -> TestMcpServer.text("created")),
                new Definition("delete_all", null, args -> TestMcpServer.text("deleted")),
                new Definition("bad.name", true, args -> TestMcpServer.text("x")));
        registry = new McpRegistry(new McpSettings(List.of(settings(server.url(), allow, deny, maxTools)), java.time.Duration.ofSeconds(1)), FAST);
        for (int i = 0; i < 1000 && registry.state("s") != McpConnection.State.READY; i++) {
            Thread.sleep(10);
        }
        assertEquals(McpConnection.State.READY, registry.state("s"));
        Thread.sleep(50);
    }

    private List<String> names() {
        return registry.tools().stream().map(Tool::name).sorted().toList();
    }

    @Test
    void onlyReadOnlyToolsAreVisibleByDefault() throws Exception {
        open(Set.of(), Set.of(), 64);
        assertEquals(List.of("mcp__s__bad_name_11ac3b82", "mcp__s__search"), names());
        assertTrue(registry.find("mcp__s__delete_all").isEmpty());
    }

    @Test
    void allowAddsWritesAndDenyWins() throws Exception {
        open(Set.of("create", "delete_all"), Set.of("delete_all"), 64);
        assertEquals(List.of("mcp__s__bad_name_11ac3b82", "mcp__s__create", "mcp__s__search"), names());
    }

    @Test
    void maxToolsKeepsExplicitlyAllowedOnes() throws Exception {
        open(Set.of("create"), Set.of(), 1);
        assertTrue(names().contains("mcp__s__create"));
        assertEquals(1, names().size() , names().toString());
    }

    @Test
    void toolsAreExclusiveShareOneServerAndCallThrough() throws Exception {
        open(Set.of("create"), Set.of(), 64);
        Tool search = registry.find("mcp__s__search").orElseThrow();
        assertFalse(search.isConcurrencySafe(new Json.Null()), "exclusive, so replay does not depend on the server");
        assertEquals("mcp:s", search.server());
        assertEquals(2, search.serverConcurrency());
        assertTrue(search.schema() instanceof Json.Obj);
        var context = new ToolContext(new Principal("u"), new tantalum.value.id.SessionId("s"), new tantalum.shell.plugin.Workspace(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"))));
        assertEquals("found", search.run(context, new Json.Obj(java.util.Map.of())).text());
        assertEquals(List.of("search"), server.calls);
    }

    @Test
    void catalogSortsAndKeepsRegisteredToolsFirst() throws Exception {
        open(Set.of(), Set.of(), 64);
        ToolCatalog catalog = new ToolCatalog();
        catalog.setSource(registry);
        assertEquals(List.of("mcp__s__bad_name_11ac3b82", "mcp__s__search"), catalog.specs().stream().map(spec -> spec.name()).toList());
        assertTrue(catalog.find("mcp__s__search").isPresent());
        assertFalse(catalog.safety().isConcurrencySafe("mcp__s__search", new Json.Null()));
        Tool found = catalog.find("mcp__s__search").orElseThrow();
        assertEquals(2, found.serverConcurrency());
        // 并发名额按 mcp:s，插件页和开关按配置里的名字 s
        assertEquals("mcp:s", found.server());
        assertEquals("s", catalog.serverName(found));
        assertEquals(List.of(new McpRegistry.ServerStatus("s", registry.state("s").name())), registry.servers());
    }

    @Test
    void noteOnlyWhenServersAreConfigured() throws Exception {
        assertEquals("", new McpRegistry(McpSettings.none()).fixedNote());
        open(Set.of(), Set.of(), 64);
        assertTrue(registry.fixedNote().contains("untrusted"));
    }

    @Test
    void unreachableServerDoesNotBlockConstruction() {
        registry = new McpRegistry(new McpSettings(List.of(settings("http://127.0.0.1:9/mcp", Set.of(), Set.of(), 64)), java.time.Duration.ofSeconds(1)), FAST);
        assertEquals(List.of(), registry.tools());
    }

    /** 需求 2.4：名字被改写的工具调用时仍用原名。 */
    @Test
    void aRenamedToolIsCalledByItsOriginalName() throws Exception {
        open(Set.of(), Set.of(), 64);
        Tool renamed = registry.find(McpToolFilter.publicName("s", "bad.name")).orElseThrow();
        assertFalse(renamed.run(null, new Json.Obj(java.util.Map.of())).error());
        assertEquals(List.of("bad.name"), server.calls);
    }

    /** 需求 2.5：服务器不可用时最多等配置的时间；可用时等到工具出现。 */
    @Test
    void awaitReadyIsBoundedAndSeesTheToolsOfAReachableServer() throws Exception {
        server = new TestMcpServer(new Definition("search", true, args -> TestMcpServer.text("found")));
        registry = new McpRegistry(new McpSettings(List.of(settings(server.url(), Set.of(), Set.of(), 64)), java.time.Duration.ofSeconds(10)));
        registry.awaitReady();
        assertFalse(registry.tools().isEmpty(), "tools are there right after the first attempt");
        registry.close();

        McpSettings.Server silent = settings("http://10.255.255.1:9/mcp", Set.of(), Set.of(), 64);
        registry = new McpRegistry(new McpSettings(List.of(silent), java.time.Duration.ofMillis(200)));
        long start = System.nanoTime();
        registry.awaitReady();
        assertTrue(java.time.Duration.ofNanos(System.nanoTime() - start).toMillis() < 2000);
        assertTrue(registry.tools().isEmpty());
    }
}
